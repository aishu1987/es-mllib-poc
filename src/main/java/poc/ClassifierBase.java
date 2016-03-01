package poc;

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.mllib.classification.ClassificationModel;
import org.apache.spark.mllib.classification.LogisticRegressionModel;
import org.apache.spark.mllib.classification.LogisticRegressionWithLBFGS;
import org.apache.spark.mllib.classification.NaiveBayes;
import org.apache.spark.mllib.classification.NaiveBayesModel;
import org.apache.spark.mllib.classification.SVMModel;
import org.apache.spark.mllib.classification.SVMWithSGD;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.pmml.PMMLExportable;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.storage.StorageLevel;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantStringTerms;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTerms;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.SignificanceHeuristicBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.spark.rdd.api.java.JavaEsSpark;

import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.search.aggregations.AggregationBuilders.*;
import static scala.collection.JavaConversions.*;
import scala.Serializable;
import scala.Tuple2;

/**
 * This needs token-plugin installed: https://github.com/brwe/es-token-plugin
 * <p/>
 * Trains a naive bayes classifier and stores the resulting model parameters back to elasticsearch.
 * <p/>
 * <p/>
 * see https://gist.github.com/brwe/3cc40f8f3d6e8edc48ac for details on how to use
 */
class ClassifierBase implements Serializable {


    protected static final Properties ES_SPARK_CFG = new Properties();

    static {
        ES_SPARK_CFG.setProperty("es.nodes", "localhost");
        ES_SPARK_CFG.setProperty("es.port", "9200");
        ES_SPARK_CFG.setProperty("es.read.unmapped.fields.ignore", "false");
    }

    protected static final transient SparkConf conf = new SparkConf().setAll(propertiesAsScalaMap(ES_SPARK_CFG)).setMaster(
            "local[3]").setAppName("estest");

    protected static transient JavaSparkContext sc = null;


    protected void trainClassifiersAndWriteModels(String[] featureTerms, Client client, String indexAndType, String modelSuffix) throws IOException {
        // get for each document a vector of tfs for the featureTerms
        JavaPairRDD<String, Map<String, Object>> esRDD = JavaEsSpark.esRDD(sc, indexAndType,
                restRequestBody(featureTerms, true));

        // convert to labeled point (label + vector)
        JavaRDD<LabeledPoint> corpus = convertToLabeledPoint(esRDD, featureTerms.length);

        // Split data into training (60%) and test (40%).
        // from https://spark.apache.org/docs/1.2.1/mllib-naive-bayes.html
        JavaRDD<LabeledPoint>[] splits = corpus.randomSplit(new double[]{6, 4});
        JavaRDD<LabeledPoint> training = splits[0];
        JavaRDD<LabeledPoint> test = splits[1];

        // try naive bayes
        System.out.println("train Naive Bayes ");
        training.persist(StorageLevel.MEMORY_AND_DISK());
        final NaiveBayesModel model = NaiveBayes.train(training.rdd(), 1.0);
        evaluate(test, model);

        System.out.println("write model parameters for naive bayes");
        // index parameters in separate doc
        doAndPrintError(client.prepareIndex("model", "params", "naive_bayes_model_params" + modelSuffix).setSource(getParamsDocSource(model, featureTerms)).execute(),
                "naive_bayes_model" + modelSuffix,
                "could not store parameter doc");

        // try svm
        System.out.println("train SVM ");
        final SVMModel svmModel = SVMWithSGD.train(training.rdd(), 10, 0.1, 0.01, 1);
        evaluate(test, svmModel);

        System.out.println("write model parameters for svm");
        // index parameters in separate doc
        doAndPrintError(client.prepareIndex("model", "params", "svm_model_params" + modelSuffix).setSource(getParamsDocSource(svmModel, featureTerms)).execute(),
                "svm_model" + modelSuffix,
                "could not store parameter doc");
        client.prepareIndex("model", "pmml", "svm_model_pmml" + modelSuffix).setSource(getPMMLSource(svmModel, featureTerms)).get();
        //System.out.println(svmModel.toPMML());
        final LogisticRegressionModel lgModel = new LogisticRegressionWithLBFGS()
                .setNumClasses(2)
                .run(training.rdd());
        evaluate(test, lgModel);
        doAndPrintError(client.prepareIndex("model", "params", "lr_model_params" + modelSuffix).setSource(getParamsDocSource(lgModel, featureTerms)).execute(),
                "lr_model" + modelSuffix,
                "could not store parameter doc");
        client.prepareIndex("model", "pmml", "lr_model_pmml" + modelSuffix).setSource(getPMMLSource(lgModel, featureTerms)).get();

    }

    private XContentBuilder getPMMLSource(PMMLExportable model, String[] featureTerms) throws IOException {
        return jsonBuilder().startObject()
                .field("pmml", model.toPMML())
                .field("features", removeQuotes(featureTerms))
                .endObject();
    }

    private void doAndPrintError(ListenableActionFuture response, String loggerName, String message) {
        try {
            response.actionGet();
        } catch (Throwable t) {
            ESLoggerFactory.getLogger(loggerName).info(message, t);
        }
    }

    private XContentBuilder getParamsDocSource(NaiveBayesModel model, String[] featureTerms) throws IOException {
        return jsonBuilder().startObject()
                .field("features", removeQuotes(featureTerms))
                .field("pi", model.pi())
                .field("thetas", model.theta())
                .field("labels", model.labels())
                .endObject();
    }

    private String[] removeQuotes(String[] featureTerms) {
        String[] featuresWithoutQuotes = new String[featureTerms.length];
        for (int i = 0; i < featureTerms.length; i++) {
            featuresWithoutQuotes[i] = featureTerms[i].replace("\"", "");
        }
        return featuresWithoutQuotes;
    }

    private XContentBuilder getParamsDocSource(SVMModel model, String[] featureTerms) throws IOException {

        XContentBuilder builder = jsonBuilder().startObject()
                .field("features", removeQuotes(featureTerms))
                .field("weights", model.weights().toArray())
                .field("intercept", model.intercept())
                .field("labels", new double[]{1,0})
                .endObject();
        return builder;
    }

    private XContentBuilder getParamsDocSource(LogisticRegressionModel model, String[] featureTerms) throws IOException {

        XContentBuilder builder = jsonBuilder().startObject()
                .field("features", removeQuotes(featureTerms))
                .field("weights", model.weights().toArray())
                .field("intercept", model.intercept())
                .field("labels", new double[]{1,0})
                .endObject();
        return builder;
    }

    private void evaluate(JavaRDD<LabeledPoint> test, final ClassificationModel model) {
        JavaRDD predictionAndLabel = test.map(new Function<LabeledPoint, Tuple2<Double, Double>>() {
            @Override
            public Tuple2<Double, Double> call(LabeledPoint s) {
                return new Tuple2<>(model.predict(s.features()), s.label());
            }
        });
        double accuracy = 1.0 * predictionAndLabel.filter(new Function<Tuple2<Double, Double>, Boolean>() {
            @Override
            public Boolean call(Tuple2<Double, Double> s) {
                return s._1().equals(s._2());
            }
        }).count() / test.count();
        System.out.println("accuracy for " + model.getClass().getName() + ": " + accuracy);
    }

    private JavaRDD<LabeledPoint> convertToLabeledPoint(JavaPairRDD<String, Map<String, Object>> esRDD, final int vectorLength) {
        JavaRDD<LabeledPoint> corpus = esRDD.map(
                new Function<Tuple2<String, Map<String, Object>>, LabeledPoint>() {
                    @Override
                    public LabeledPoint call(Tuple2<String, Map<String, Object>> dataPoint) {
                        Double doubleLabel = getLabel(dataPoint);
                        return new LabeledPoint(doubleLabel, Vectors.sparse(vectorLength, getIndices(dataPoint), getValues(dataPoint)));
                    }

                    private double[] getValues(Tuple2<String, Map<String, Object>> dataPoint) {
                        // convert ArrayList to double[]
                        Map<String, Object> indicesAndValues = (Map) (((ArrayList) dataPoint._2().get("vector")).get(0));
                        ArrayList valuesList = (ArrayList) indicesAndValues.get("values");
                        if (valuesList == null) {
                            return new double[0];
                        } else {
                            double[] values = new double[valuesList.size()];
                            for (int i = 0; i < valuesList.size(); i++) {
                                values[i] = ((Number) valuesList.get(i)).doubleValue();
                            }
                            return values;
                        }
                    }

                    private int[] getIndices(Tuple2<String, Map<String, Object>> dataPoint) {
                        // convert ArrayList to int[]
                        Map<String, Object> indicesAndValues = (Map) (((ArrayList) dataPoint._2().get("vector")).get(0));
                        ArrayList indicesList = (ArrayList) indicesAndValues.get("indices");
                        if (indicesList == null) {
                            return new int[0];
                        } else {
                            int[] indices = new int[indicesList.size()];
                            for (int i = 0; i < indicesList.size(); i++) {
                                indices[i] = ((Number) indicesList.get(i)).intValue();
                            }
                            return indices;
                        }
                    }

                    private Double getLabel(Tuple2<String, Map<String, Object>> dataPoint) {
                        // convert string to double label
                        String label = (String) ((ArrayList) dataPoint._2().get("label")).get(0);
                        return label.equals("positive") ? 1.0 : 0;
                    }
                }
        );
        // print some lines so we know how the data looks like
        System.out.println("example document vector: " + corpus.take(1));
        return corpus;
    }

    // request body for vector representation of documents
    private String restRequestBody(String[] featureTerms, boolean fieldDataFields) {
        return "{\n" +
                "  \"script_fields\": {\n" +
                "    \"vector\": {\n" +
                "      \"script\": \"sparse_vector\",\n" +
                "      \"lang\": \"native\",\n" +
                "      \"params\": {\n" +
                "        \"features\": " + Arrays.toString(featureTerms) +
                "        ,\n" +
                "        \"field\": \"text\",\n" +
                "        \"fieldDataFields\": " + fieldDataFields + "\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"fields\": [\n" +
                "    \"label\"\n" +
                "  ]\n" +
                "}";
    }


    //
    protected static String[] getAllTermsAsStringList(String index) {
        String url = "http://localhost:9200/" + index + "/_allterms/text?size=10000000";
        URLConnection connection = null;
        try {
            connection = new URL(url).openConnection();
            connection.setRequestProperty("Accept-Charset", "UTF-8");
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is), 1);
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            rd.close();
            String[] terms = response.toString().split(",");
            terms[0] = terms[0].substring(10);
            terms[terms.length - 1] = terms[terms.length - 1].substring(0, terms[terms.length - 1].length() - 3);
            Arrays.sort(terms);
            return terms;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
        }
    }

    //
    protected String[] getSignificantTermsAsStringList(int numTerms, SignificanceHeuristicBuilder heuristic, Client client, String index) {

        SearchResponse searchResponse = client.prepareSearch(index).addAggregation(terms("classes").field("label").subAggregation(significantTerms("features").field("text").significanceHeuristic(heuristic).size(numTerms / 2))).get();
        List<Terms.Bucket> labelBucket = ((Terms) searchResponse.getAggregations().asMap().get("classes")).getBuckets();
        Collection<SignificantTerms.Bucket> significantTerms = ((SignificantStringTerms) (labelBucket.get(0).getAggregations().asMap().get("features"))).getBuckets();
        List<String> features = new ArrayList<>();
        addFeatures(significantTerms, features);
        significantTerms = ((SignificantStringTerms) (labelBucket.get(1).getAggregations().asMap().get("features"))).getBuckets();
        addFeatures(significantTerms, features);
        String[] featureTerms = features.toArray(new String[features.size()]);
        Arrays.sort(featureTerms);
        return featureTerms;
    }

    private void addFeatures(Collection<SignificantTerms.Bucket> significantTerms, List<String> featureArray) {
        for (SignificantTerms.Bucket bucket : significantTerms) {
            featureArray.add("\"" + bucket.getKey() + "\"");
        }
    }
}
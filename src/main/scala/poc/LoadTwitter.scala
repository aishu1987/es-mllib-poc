package poc

import java.net.InetAddress

import org.apache.spark.{SparkConf, SparkContext}
import org.elasticsearch.client.Requests
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.IndexNotFoundException
import org.elasticsearch.node.NodeBuilder
import org.elasticsearch.spark.rdd.EsSpark

/**
 * Indexes tweets into index sentiment140.
 * Documents look like this:
  * {
  * "label": "negative",
  * "text": "\"I am disgustingly full. I hate this feeling! \""
  * }
 *
 * Data is from http://help.sentiment140.com/for-students
 */
object LoadTwitter {
  def main(args: Array[String]) = {
    var client = TransportClient.builder().build()
      .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9300));
    println("deleting index sentiment140")
    try {
      client.admin().indices().prepareDelete("sentiment140").get()
    } catch {
      case e: IndexNotFoundException => println("index sentiment140 does not exist")
    }
    client.admin().indices().prepareCreate("sentiment140").addMapping("tweets", "{\"tweets\":{\"properties\":{\"text\":{\"type\":\"string\", \"term_vector\":\"yes\"}}}}").get()
    client.admin.cluster.health(Requests.clusterHealthRequest("sentiment140").waitForGreenStatus()).actionGet()
    client.close()
    val path = if (args.length == 1) args(0) else "./data/"

    new LoadTwitter().indexData(path)
  }
}

class LoadTwitter extends Serializable {

  @transient lazy val sc = new SparkContext(new SparkConf().setAll(Map("es.nodes" -> "localhost", "es.port" -> "9200")).setMaster("local").setAppName("movie-reviews"))

  def indexData(path: String): Unit = {
    try {
      val csv = sc.textFile(path + "training.1600000.processed.noemoticon.csv")
      val rows = csv.map(line => line.split(",").map(_.trim)).map(line =>
        try {
        Opinion(if (line.apply(0).equals("\"4\"")) "positive" else "negative", line.apply(5))
        } catch {
          case e: ArrayIndexOutOfBoundsException => println("a weird line:" + line.apply(0) + line.apply(1) + line.apply(2) + line.apply(3) + line.apply(4))
            Opinion(if (line.apply(0).equals("\"4\"")) "positive" else "negative", "")
        }
      )
      EsSpark.saveToEs(rows, "sentiment140/tweets")

    } finally {
      sc.stop()
    }
  }


  case class Opinion(label: String, text: String)

}
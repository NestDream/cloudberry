package actor

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import edu.uci.ics.cloudberry.zion.model.schema.TimeField
import models.{GeoLevel, UserRequest}
import play.api.libs.json.{JsArray, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

class NeoActor(out: Option[ActorRef], val berryClient: ActorRef)(implicit ec: ExecutionContext) extends Actor with ActorLogging {

  import NeoActor._
  import akka.pattern.ask

  import scala.concurrent.duration._

  implicit val timeout: Timeout = Timeout(5.seconds)

  override def receive: Receive = {
    //TODO add the json validator
    case json: JsValue =>
      val userRequest = json.as[UserRequest]
      tellBerry(userRequest, sender())
    case userRequest: UserRequest =>
      tellBerry(userRequest, sender())
  }

  private def tellBerry(userRequest: UserRequest, curSender: ActorRef): Unit = {
    if (userRequest.mergeResult) {
      val map = generateCBerryRequest(userRequest)
      val fResponse = Future.traverse(map) { kv =>
        (berryClient ? kv._2).mapTo[JsValue].map(json => Json.obj("key" -> kv._1, "value" -> json))
      }
      fResponse.map { jsVals =>
        out.getOrElse(curSender) ! JsArray(jsVals.toSeq)
      }
    } else {
      for (berryRequest <- generateCBerryRequest(userRequest)) {
        (berryClient ? berryRequest._2).mapTo[JsValue].map { json =>
          out.getOrElse(curSender) ! Json.obj("key" -> berryRequest._1.toString, "value" -> json)
        }
      }
    }
  }
}

object NeoActor {

  def props(out: ActorRef, berryClient: ActorRef)(implicit ec: ExecutionContext) = Props(new NeoActor(Some(out), berryClient))

  def props(berryClient: ActorRef)(implicit ec: ExecutionContext) = Props(new NeoActor(None, berryClient))

  object RequestType extends Enumeration {
    val ByPlace = Value("byPlace")
    val ByTime = Value("byTime")
    val ByHashTag = Value("byHashTag")
    val Sample = Value("sample")
  }

  def generateCBerryRequest(userRequest: UserRequest): Map[RequestType.Value, JsValue] = {
    val filterJSON = getFilter(userRequest)

    val byGeo = Json.parse(
      s"""
         |{
         | "dataset": "twitter.ds_tweet",
         | $filterJSON,
         | "group": {
         |   "by": [
         |      {
         |        "field": "geo",
         |        "apply": {
         |          "name": "level",
         |          "args": {
         |            "level": "${userRequest.geoLevel}"
         |          }
         |        },
         |        "as": "${userRequest.geoLevel}"
         |      }
         |   ],
         |   "aggregate": [
         |     {
         |       "field": "*",
         |       "apply": {
         |         "name": "count"
         |       },
         |       "as": "count"
         |     }
         |    ]
         |  }
         |}
         |""".stripMargin)

    val byTime = Json.parse(
      s"""
         |{
         | "dataset": "twitter.ds_tweet",
         | $filterJSON,
         | "group": {
         |   "by": [
         |      {
         |        "field": "create_at",
         |        "apply": {
         |          "name": "interval",
         |          "args": {
         |            "unit": "${userRequest.timeBin}"
         |          }
         |        },
         |        "as": "${userRequest.timeBin}"
         |      }
         |    ],
         |   "aggregate": [
         |     {
         |       "field": "*",
         |       "apply": {
         |         "name": "count"
         |       },
         |       "as": "count"
         |     }
         |    ]
         |  }
         |}
    """.stripMargin
    )

    val byHashTag = Json.parse(
      s"""
         |{
         | "dataset": "twitter.ds_tweet",
         | $filterJSON,
         | "unnest" : { "hashtags": "tag"},
         | "group": {
         |    "by": [
         |      {
         |        "field": "tag"
         |      }
         |    ],
         |    "aggregate": [
         |      {
         |        "field" : "*",
         |        "apply" : {
         |          "name": "count"
         |        },
         |        "as" : "count"
         |      }
         |    ]
         |  },
         |  "select" : {
         |    "order" : [ "-count"],
         |    "limit": 50,
         |    "offset" : 0
         |  }
         |}
       """.stripMargin
    )

    val sampleTweet = Json.parse(
      s"""
         |{
         |  "dataset": "twitter.ds_tweet",
         |  $filterJSON,
         |   "select" : {
         |    "order" : [ "-create_at"],
         |    "limit": 10,
         |    "offset" : 0,
         |    "field": ["create_at", "id", "user.id"]
         |  }
         |}
       """.stripMargin
    )
    import RequestType._
    Map(ByPlace -> byGeo, ByTime -> byTime, ByHashTag -> byHashTag, Sample -> sampleTweet)
  }

  private def getFilter(userRequest: UserRequest): String = {
    val spatialField = getLevel(userRequest.geoLevel)
    val keywords = userRequest.keywords.map(_.replace("\"", "").trim)
    s"""
       |"filter": [
       |  {
       |    "field": "geo_tag.$spatialField",
       |    "relation": "in",
       |    "values": [${userRequest.geoIds.mkString(",")}]
       |  },
       |  {
       |    "field": "create_at",
       |    "relation": "inRange",
       |    "values": [
       |      "${TimeField.TimeFormat.print(userRequest.timeInterval.getStart)}",
       |      "${TimeField.TimeFormat.print(userRequest.timeInterval.getEnd)}"
       |    ]
       |  },
       |  {
       |    "field": "text",
       |    "relation": "contains",
       |    "values": [
       |      ${keywords.map("\"" + _ + "\"").mkString(",")}
       |    ]
       |  }
       | ]
     """.stripMargin
  }

  private def getLevel(level: GeoLevel.Level): String = {
    level match {
      case GeoLevel.State => "stateID"
      case GeoLevel.County => "countyID"
      case GeoLevel.City => "cityID"
    }
  }
}

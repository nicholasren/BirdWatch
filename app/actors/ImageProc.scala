package actors

import akka.actor.{ Actor, Props, ActorRef, ActorLogging }
import play.api.libs.ws.WS
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import reactivemongo.api.gridfs._
import reactivemongo.api.gridfs.Implicits._
import akka.event.Logging

import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import akka.routing.RoundRobinRouter
import scala.concurrent.duration._

import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import models._
import utils._

/** Actors related to image processing */
object ImageProc {

  case class Proc(client: ActorRef, t: Tweet)  
  case class DoneProc(p: Proc)

  class Supervisor(eventStream: akka.event.EventStream) extends Actor with ActorLogging {
    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {
      case _: ArithmeticException        => Resume
      case _: javax.imageio.IIOException => Restart
      case _: NullPointerException       => Restart
      case _: IllegalArgumentException   => Stop
      case _: Exception                  => Restart
    }
    
    override val log = Logging(context.system, this)
     
    override def preStart() {
      log.debug("Starting")
    }
    override def preRestart(reason: Throwable, message: Option[Any]) {
      log.error(reason, "Restarting due to [{}] when processing [{}]", reason.getMessage, message.getOrElse(""))
    }
    
    val conversionRouter = context.actorOf(Props[ImageProc.ConversionActor].withRouter(RoundRobinRouter(nrOfInstances = 5)), name = "ConvRouter")
    
    val retrievalActor = context.actorOf(Props(new ImageProc.RetrievalActor( Some(conversionRouter))), "Retriever")
    
   /** Receives Tweet. If Tweet doesn't have a MongoID yet (not previously processed) it will be forwarded
    *  to Image Retrieval Actor.
    */
    def receive = {
      case t: Tweet if (t.id == None) => {
        eventStream.publish(Proc(sender, t))    // acknowledge receipt (testing)
        
        retrievalActor ! Proc(sender, t)        // forward tweet to retrieval actor (child)
      }
      case DoneProc(p: Proc) => {
        log.debug("DONE: " + p.t.profile_image_url)
        
        eventStream.publish(DoneProc(p))
        eventStream.publish(p.t)
        ActorStage.tweetStreamSubscriber ! p.t
      }
    }
  }
  
 /** Image retrieval actor, receives Tweets, retrieves the Twitter profile images for each user and passes them on to conversion actor. 
  *  @param next Actor reference to pass message on to (Image conversion actor)
  */
  class RetrievalActor(next: Option[ActorRef]) extends Actor with ActorLogging {
    override val log = Logging(context.system, this)
     
    override def preStart() {
      log.debug("Starting")
    }
    override def preRestart(reason: Throwable, message: Option[Any]) {
      log.error(reason, "Restarting due to [{}] when processing [{}]", reason.getMessage, message.getOrElse(""))
    }
    
   /** Receives tweet, retrieves associated profile Image within WS Promise which upon completion
    *  asynchronously forwards Image data to Image conversion actor.
    */
    def receive = {
      case p: Proc => {
        log.debug("RetrievalActor received request for " + p.t.profile_image_url)
        
        WS.url("http://" + p.t.profile_image_url).get().map { r =>
          log.debug("Image retrieved")
          
          val body = r.getAHCResponse.getResponseBodyAsBytes // body as byte array
          
          next match {
            case Some(actor) => actor ! (p, body)
            case None => 
          }
        }
      }
    }
  }
  
 /** from: https://gist.github.com/mbbx6spp/3004211
  *  Pass in Image object of the original to here with new dimensions.
  *  Produces other image object that can be saved to disk in the appropriate
  *  folder scheme of your choice.
  */
  def resizeImage(image: BufferedImage, newWidth: Int, newHeight: Int): BufferedImage = {
    // This preserves alpha channel, use BufferedImage.TYPE_INT_ARGB to not preserve alpha
    val newImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = newImage.createGraphics
    graphics.drawImage(image, 0, 0, newWidth, newHeight, null)
    graphics.dispose()
    newImage
  }
  
  /** Image conversion actor, receives (Tweet, Array[Byte]), converts images and saves them into MongoDB. */
  class ConversionActor extends Actor with ActorLogging {
    override val log = Logging(context.system, this)
     
    override def preStart() {
      log.debug("Starting")
    }
    override def preRestart(reason: Throwable, message: Option[Any]) {
      log.error(reason, "Restarting due to [{}] when processing [{}]", reason.getMessage, message.getOrElse(""))
    }
    
    /** Converts and downsizes received Array[Byte] into PNG of dimensions 150*150px, writes image to GridFS */
    def receive = {
      case (p: Proc, data: Array[Byte]) => {
        log.debug("Received Image " + p.t.profile_image_url)
        val contentType = "image/png"
        val fileName = p.t.tweet_id + ".png"

        val img: BufferedImage = ImageIO.read(new ByteArrayInputStream(data))
        val resizedImg = resizeImage(img, 80, 80)
        //ImageIO.write(resizedImg, "png", new File( "/Users/mn/imageTemp/" + fileName))

        val outStream: ByteArrayOutputStream = new ByteArrayOutputStream()
        ImageIO.write(resizedImg, "png", outStream)
        
        // create Enumerator from body of WS request
        val enumerator = Enumerator(outStream.toByteArray)
        // saves content of enumerator into GridFS
        Mongo.imagesGridFS.save(enumerator, DefaultFileToSave(fileName, Some(contentType), None))
        
        context.actorFor("../..") ! DoneProc(p)
      }
    }
  }
  
}

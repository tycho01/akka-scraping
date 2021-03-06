package org.tycho.scraping

import scala.collection.immutable.Queue
import scala.concurrent._
import scala.concurrent.duration._
import akka._
import akka.actor._
import akka.pattern._
import akka.util._
import akka.http._
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.ActorMaterializer
import akka.stream.actor._
import akka.stream.io._
import akka.stream.stage._
import akka.stream.scaladsl._

object Limiter {
	implicit val system = ActorSystem()

	case object WantToPass
	case object MayPass
	case object ReplenishTokens
	
	def props(
		maxAvailableTokens: Int,
		tokenRefreshPeriod: FiniteDuration,
		tokenRefreshAmount: Int
	): Props = Props(new Limiter(maxAvailableTokens, tokenRefreshPeriod, tokenRefreshAmount))
}

class Limiter(
	val maxAvailableTokens: Int,
	val tokenRefreshPeriod: FiniteDuration,
	val tokenRefreshAmount: Int
) extends Actor {
	import Limiter._
	import context.dispatcher
	import akka.actor.Status

	private var waitQueue = scala.collection.immutable.Queue.empty[ActorRef]
	private var permitTokens = maxAvailableTokens
	private val replenishTimer = system.scheduler.schedule(initialDelay = tokenRefreshPeriod, interval = tokenRefreshPeriod, receiver = self, ReplenishTokens)

	override def receive: Receive = open

	val open: Receive = {
		case ReplenishTokens =>
			permitTokens = math.min(permitTokens + tokenRefreshAmount, maxAvailableTokens)
		case WantToPass =>
			permitTokens -= 1
			sender() ! MayPass
			if (permitTokens == 0) context.become(closed)
	}

	val closed: Receive = {
		case ReplenishTokens =>
			permitTokens = math.min(permitTokens + tokenRefreshAmount, maxAvailableTokens)
			releaseWaiting()
		case WantToPass =>
			waitQueue = waitQueue.enqueue(sender())
	}

	private def releaseWaiting(): Unit = {
		val (toBeReleased, remainingQueue) = waitQueue.splitAt(permitTokens)
		waitQueue = remainingQueue
		permitTokens -= toBeReleased.size
		toBeReleased foreach (_ ! MayPass)
		if (permitTokens > 0) context.become(open)
	}

	override def postStop(): Unit = {
		replenishTimer.cancel()
		waitQueue foreach (_ ! Status.Failure(new IllegalStateException("limiter stopped")))
	}
}


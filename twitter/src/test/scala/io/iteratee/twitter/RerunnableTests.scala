package io.iteratee.twitter

import cats.Eq
import com.twitter.conversions.time._
import io.catbird.util.Rerunnable
import io.iteratee.tests.{ EnumerateeSuite, IterateeErrorSuite, ModuleSuite, StackSafeEnumeratorSuite, eqThrowable }

trait RerunnableSuite extends ModuleSuite[Rerunnable] with RerunnableModule {
  def monadName: String = "Rerunnable"

  implicit def eqF[A: Eq]: Eq[Rerunnable[A]] = Rerunnable.rerunnableEqWithFailure[A](2.seconds)
}

class RerunnableEnumerateeTests extends EnumerateeSuite[Rerunnable] with RerunnableSuite
class RerunnableEnumeratorTests extends StackSafeEnumeratorSuite[Rerunnable] with RerunnableSuite
class RerunnableIterateeTests extends IterateeErrorSuite[Rerunnable, Throwable] with RerunnableSuite

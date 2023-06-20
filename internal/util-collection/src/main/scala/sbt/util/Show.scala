/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.util

trait Show[A] {
  def show(a: A): String
}
object Show {
  def apply[A](f: A => String): Show[A] = a => f(a)

  def fromToString[A]: Show[A] = _.toString
}

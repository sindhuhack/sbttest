/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package xsbt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._

class IPCSpec extends AnyFlatSpec {
  "server" should "find free open ports and close them" in {
    noException should be thrownBy {
      val server = IPC.unmanagedServer
      (1 until 500).foreach { index =>
        try {
          IPC.client(server.port)(identity)
        } catch {
          case e: Throwable =>
            val message = s"failure index is $index"
            info(message)
            throw e
        }
      }
      server.close()
    }
  }
}

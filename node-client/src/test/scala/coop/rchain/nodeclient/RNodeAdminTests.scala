package coop.rchain.nodeclient

import org.scalatra.test.scalatest._

class RNodeAdminTests extends ScalatraFunSuite {

  addServlet(classOf[RNodeAdmin], "/*")

  test("GET / on RNodeAdmin should return status 200") {
    get("/") {
      status should equal (200)
    }
  }

}

package coop.rchain.nodeclient

import org.scalatra._

class RNodeAdmin extends ScalatraServlet {

  get("/") {
    views.html.hello()
  }

}

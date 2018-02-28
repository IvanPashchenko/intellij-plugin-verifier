package org.jetbrains.plugins.verifier.service.server.servlets

import com.jetbrains.pluginverifier.misc.HtmlBuilder
import com.jetbrains.pluginverifier.misc.MemoryInfo
import com.jetbrains.pluginverifier.repository.cleanup.fileSize
import org.jetbrains.plugins.verifier.service.service.BaseService
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * The servlet handling requests of the server status, health and parameters.
 */
class InfoServlet : BaseServlet() {

  companion object {
    private val DATE_FORMAT = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
  }

  override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val path = getPath(req, resp) ?: return
    if (path.endsWith("control-service")) {
      processServiceControl(req, resp)
    } else {
      processStatus(resp)
    }
  }

  private fun processServiceControl(req: HttpServletRequest, resp: HttpServletResponse) {
    val adminPassword = req.getParameter("admin-password")
    if (adminPassword == null || adminPassword != serverContext.authorizationData.serviceAdminPassword) {
      resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Incorrect password")
      return
    }
    val serviceName = req.getParameter("service-name")
    if (serviceName == null) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Service name is not specified")
      return
    }
    val command = req.getParameter("command")
    when (command) {
      "start" -> changeServiceState(serviceName, resp) { it.start() }
      "resume" -> changeServiceState(serviceName, resp) { it.resume() }
      "pause" -> changeServiceState(serviceName, resp) { it.pause() }
      else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown command: $command")
    }
  }

  private fun changeServiceState(serviceName: String, resp: HttpServletResponse, action: (BaseService) -> Boolean) {
    val service = serverContext.allServices.find { it.serviceName == serviceName }
    if (service == null) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Service $serviceName is not found")
    } else {
      if (action(service)) {
        sendOk(resp, "Service's $serviceName state is changed to ${service.getState()}")
      } else {
        resp.sendError(HttpServletResponse.SC_CONFLICT, "Service $serviceName can't be paused")
      }
    }
  }

  private fun processStatus(resp: HttpServletResponse) {
    sendContent(resp, generateStatusPage(), "text/html")
  }

  private fun generateStatusPage(): ByteArray {
    val byteOS = ByteArrayOutputStream()
    val printWriter = PrintWriter(byteOS)
    HtmlBuilder(printWriter).apply {
      html {
        head {
          title("Server status")
          style {
            +"""table, th, td {
              border: 1px solid black;
              border-collapse: collapse;
            }"""
          }
        }
        body {
          div {
            h1 {
              +("Plugin Verifier Service " + appVersion)
            }
            h2 {
              +"Runtime parameters:"
            }
            ul {
              serverContext.startupSettings.forEach { s ->
                li {
                  +(s.key + " = " + if (s.encrypted) "*****" else s.get())
                }
              }
            }

            h2 {
              +"Status:"
            }
            ul {
              val (totalMemory, freeMemory, usedMemory, maxMemory) = MemoryInfo.getRuntimeMemoryInfo()
              li { +"Total memory: $totalMemory" }
              li { +"Free memory: $freeMemory" }
              li { +"Used memory: $usedMemory" }
              li { +"Max memory: $maxMemory" }

              val totalUsage = serverContext.applicationHomeDirectory.fileSize
              li { +"Total disk usage: $totalUsage" }
            }

            h2 {
              +"Services:"
            }
            ul {
              serverContext.allServices.forEach { service ->
                val serviceName = service.serviceName
                li {
                  +(serviceName + " - ${service.getState()}")
                  form("control-$serviceName", "display: inline;", "/info/control-service") {
                    input("submit", "command", "start")
                    input("submit", "command", "resume")
                    input("submit", "command", "pause")
                    input("hidden", "service-name", serviceName)
                    +"Admin password: "
                    input("password", "admin-password")
                  }
                }
              }
            }
            h2 {
              +"Available IDEs: "
            }
            ul {
              serverContext.ideKeeper.getAvailableIdeVersions().forEach {
                li {
                  +it.toString()
                }
              }
            }

            h2 {
              +"Running tasks"
            }
            table("width: 100%") {
              tr {
                th { +"ID" }
                th { +"Task name" }
                th { +"Start time" }
                th { +"State" }
                th { +"Message" }
                th { +"Completion %" }
                th { +"Total time (ms)" }
              }

              serverContext.taskManager.getRunningTasks().forEach { taskStatus ->
                tr {
                  td { +taskStatus.taskId.toString() }
                  td { +taskStatus.presentableName }
                  td { +DATE_FORMAT.format(taskStatus.startTime) }
                  td { +taskStatus.state.toString() }
                  td { +taskStatus.progress.text }
                  td { +String.format("%.2f", taskStatus.progress.fraction) }
                  td { +taskStatus.elapsedTime.toMillis().toString() }
                }
              }
            }
          }
        }
      }
    }
    printWriter.close()
    return byteOS.toByteArray()
  }

}

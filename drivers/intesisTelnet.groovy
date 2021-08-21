/**
 *  Intesis Telnet 0.1
 *
 * Author: ERS
 *       based off device work by Martin Blomgren
 * Last update: 2021-08-21
 *
 * Thanks to James Nimmo for the massive work with the Python IntesisHome module
 * (https://github.com/jnimmo/pyIntesisHome)
 *
 * MIT License
 *
 * Copyright (c) 2019
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
//file:noinspection unused
//file:noinspection SpellCheckingInspection
static String version() {"v0.1"}

import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.InterfaceUtils

metadata {
	definition (name: "IntesisHome Telnet", namespace: 'imnotbob', author: "ERS") {
		capability "Configuration"
		capability "Initialize"
		capability "Refresh"
		capability "Telnet"

		capability "Actuator"

		attribute "Telnet", "string"

		command "connect"
		command "stop"

	}

	preferences {
//		if(username && password) {
//			section("Disable updating here") {
//				input "enabled", "bool", defaultValue: "true", title: "Enabled?"
//			}
//		}

		section("Logging") {
			input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
			input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
		}
	}
}

void initialize() {
	debug "initialize", ""
	state.enabled = true
	if((Boolean)state.connected) checkLastReceived()
	else connect(false)
}

void installed() {
	initialize()
}

void logsOff() {
	debug "logsOff", "debug logging disabled..."
	device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void updated() {
	unschedule()
	debug "updated", "debug logging is: ${logEnable == true}"
	debug "updated", "description logging is: ${txtEnable == true}"
	if (logEnable) runIn(1800,logsOff)
	runEvery10Minutes(checkLastReceived)

	initialize()
}

void connect(Boolean retry=true) {
	state.enabled = true
	if(!(Boolean)state.connected) {
		def myVars = parent.getParams()
		state.server = myVars.server?.toString()
		state.serverPort = myVars.port?.toInteger()
		state.token = myVars.token
		if (state.enabled && state.server && state.serverPort && state.token) {
			debug "connect:", " session to IntesisHome at ${state.server}:${state.serverPort}"

			//Connect to the IntesisHome
			try {
				state.connected = true
				//open telnet connection
				telnetConnect([termChars: [125,125], terminalType: 'VT100'], (String)state.server, (Integer)state.serverPort, (String)null, (String)null)
			}
			catch(e) {
				state.connected = false
				if (logEnable) debug "connect:", "initialize error ${e.message}"
				error "connect:", "Telnet connect failed in connect()", e
				parent.telnetDown()
				return
			}

			connectionMade()
		} else {
			//Get connection details
			if(!retry) debug("connect:", "something missing ${state.enabled} ${state.server} ${state.serverPort} ${state.token}")
			if(state.enabled) {
				parent.telnetDown(state.enabled) // this will attempt to gather missing server,port,token data (24 seconds)
				if(retry){
					runIn(40, connect)
					debug("connect:", "something missing ${state.enabled} ${state.server} ${state.serverPort} ${state.token} scheduled retry in 40 seconds")
				}
			}
		}
	} else {
		debug "connect:", "Connect called while connected, skipping"
	}
}

void connectionMade() {
	// Authenticate
	String authMsg = '{"command":"connect_req","data":{"token":' + (String)state.token + '}}'
	if (logEnable) debug "connectionMade:", "authMsg ${authMsg}"
	sendHubCommand(new hubitat.device.HubAction(authMsg, hubitat.device.Protocol.TELNET))
}

void sendMsg(String msg) {
	if(!(Boolean)state.connected) { log.warn "sendMsg when not connected" }
	sendHubCommand(new hubitat.device.HubAction(msg, hubitat.device.Protocol.TELNET))
}

void stop() {
	debug "stop", ""
	state.enabled = false
//	device.updateSetting("enabled",[value:"false",type:"bool"])
	if((Boolean)state.connected) {
		state.connected = false
		telnetClose()
	}
	debug "stop", "Telnet connection dropped..."
	sendEvent(name: "Telnet", value: "Disconnected")
	parent.telnetDown()
}

// Parse incoming device messages to generate events
void parse(String message) {
	if(!state.enabled) { stop(); return }
	if (message.contains('"uid":60002')) return // rssi

	// As we don't have any termination character we nee to put back the curly braces again
	//def msg = message + '}}'
	//log.debug "[IntesisHome] parse message: ${msg}"

	def jsonSlurper = new JsonSlurper()
	def messageJson = jsonSlurper.parseText(message + '}}')

	if (logEnable) debug "parse", "messageJson: ${messageJson}"

	switch (messageJson.command) {
		case "connect_rsp":
			if (messageJson.data.status == "ok") {
				sendEvent(name: "Telnet", value: "Connected")
				parent.telnetUp()
			}
			break

		case "status":
			//updateDeviceState(Integer deviceId, Integer uid, Short value)
			parent.updateDeviceState(messageJson.data.deviceId, messageJson.data.uid, (Short)messageJson.data.value)
			break

		case "rssi":
			// [command:rssi, data:[deviceId:0123456789, value:196]]
			break

		default:
			break
	}
	//[command:connect_rsp, data:[status:ok]]
	state.lastReceived = now()
}

void checkLastReceived() {
	Long t0 = now()
	Long t1 = state.lastReceived
	if ((Boolean)state.connected && t0 > (t1 + 300000L)) {   // 5 mins no data
		debug "checkLastReceived", "Telnet connection dropped...lastReceived: ${t1}"
		endConnection()
		telnetClose()
		parent.telnetDown(state.enabled)
	} else if(!(Boolean)state.connected) parent.telnetDown(state.enabled)
}

void endConnection() {
		state.connected = false
		state.server = null
		state.serverPort = null
		state.token = null
		sendEvent(name: "Telnet", value: "Disconnected")
}

void telnetStatus(String status) {
	if (logEnable) debug "telnetStatus", "${status}"

	if (status == "receive error: Stream is closed") {
		debug "telnetStatus", "Telnet connection dropped..."
		endConnection()
		parent.telnetDown(state.enabled)
		if(!state.enabled) debug "telnetStatus", "Telnet driver disabled"
	} else {
		if (txtEnable) debug "telnetStatus", "OK, ${status}"
		state.connected = true
		sendEvent(name: "Telnet", value: "Connected")
		parent.telnetUp()
	}
}

def setValue() {}

void refresh() {
	if (logEnable) debug "refresh", ""
}

void configure() {
	if (txtEnable) debug "Configure", "Reporting and Bindings."
	initialize()
}

private static String createLogString(String context, String message) {
	return "[IntesisHome Telnet." + context + "] " + message
}

private void error(String context, String text, Exception e) {
	error(context, text, e, true)
}

private void error(String context, String text, Exception e, Boolean remote) {
	log.error(createLogString(context, text) + e?.message)
}

private void debug(String context, String text) {
	debug(context, text, true)
}

private void debug(String context, String text, Boolean remote) {
	log.debug(createLogString(context, text))
}

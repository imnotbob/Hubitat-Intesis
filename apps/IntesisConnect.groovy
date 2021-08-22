/**
 * IntesisHome Connect
 *
 * Author: ERS
 *       based off device work by Martin Blomgren
 * Last update: 2021-08-21
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at:
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

//file:noinspection SpellCheckingInspection
//file:noinspection unused

import groovy.transform.Field

definition(
	name: 'IntesisHome Connect',
	namespace: 'imnotbob',
	author: 'ERS',
	
	description: 'Allows you to integrate your Intesis with Hubitat.',
	singleInstance: true,
	iconUrl: "",
	iconX2Url: "",
	iconX3Url: "",
	importUrl: "https://raw.githubusercontent.com/imnotbob/Hubitat-Intesis/master/apps/IntesisConnect.groovy"
)

preferences {
	page(name: 'mainPage')
}

def mainPage() {

	dynamicPage(
		name: 'mainPage',
		install: true,
		uninstall: true,
		refreshInterval: 30
	) {
		section("Authentication") {
			input "username", "text", title: "Username"
			input "password", "password", title: "Password"
		}

		if((String)settings.username && (String)settings.password) {
			section("Disable updating here") {
				input "enabled", "bool", defaultValue: "true", title: "Enabled?"
			}
		}

		section("Logging") {
			input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
			input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
		}
	}
}

void installed() {
	//log.debug('Installed')
	initialize()
}

void updated() {
	//log.debug('Updated')
	initialize()
}

void initialize() {
	//log.debug 'Initializing'
	unschedule()
	if ((Boolean)settings.logEnable) runIn(1800,logsOff)
	if((Boolean)settings.enabled) pollStatus()
	else {
		def tdev = getTelnetDev()
		if(tdev) {
			tdev.stop()
		}
	}
}

void logsOff() {
	debug "logsOff", "debug logging disabled..."
	app.updateSetting("logEnable",[value:"false",type:"bool"])
	app.updateSetting("txtEnable",[value:"false",type:"bool"])
}

void pollStatus() {
	debug("pollStatus()", "")
	if((String)settings.username && (String)settings.password && (Boolean)settings.enabled) {
		Map params = [
			uri	: INTESIS_URL,
			contentType: "application/x-www-form-urlencoded",
			body	: 'username=' + (String)settings.username + '&password=' + (String)settings.password + '&cmd={"status":{"hash":"x"},"config":{"hash":"x"}}&version=1.8.5',
			timeout: 20
		]

		try {
			asynchttpPost('handlePollResponse', params, data)
		} catch (e) {
			queuePollStatus(600)
			error("pollStatus", "Error polling", e)
		}
	} else { log.warn "missing settings ${(String)settings.username} ${(Boolean)settings.enabled}" }
}

void handlePollResponse(response, data) {
	debug("handlePollResponse()", "")
	Boolean responseError = response.hasError()

	def responseJson=null
	try {
		//if (!responseError) responseJson = parseJson(response.data)
		responseJson = parseJson(response.data)
	} catch(ignored) {}

	if (responseError || responseJson?.errorMessage) {
		queuePollStatus(900)
		error("hasError", "$responseError")
		def responseErrorStatus = response.getStatus()
		error("errorStatus", "$responseErrorStatus")
		def responseErrorData = response.getErrorData()
		error("errorData", "$responseErrorData")
		def responseErrorMessage= response.getErrorMessage()
		error("errorMessage", "$responseErrorMessage")
		error("errorjsonData", "$responseJson")
		return
	}

	//debug("responseJson", "$responseJson")

	List<Map> devList = []
	Map idevMap = [:]
	//Map devMap = [:]
	Map instMap = [:]
	atomicState.server = responseJson['config']['serverIP']
	atomicState.serverPort = responseJson['config']['serverPort']
	atomicState.token = responseJson['config']['token']

	responseJson['config']['inst'].each { installation ->

		instMap."${installation.name}" = installation
		debug("Found installation", "(${installation.name}) total installations: ${instMap.size()}")
		state.installationMap = instMap
		//log.warn "Installation: $installation"

		installation['devices'].each { device ->
			idevMap = [:] + device
			idevMap.valMap = [:]
			responseJson['status']['status'].each { status ->
				if(status.deviceId.toString() == device.id.toString())
					idevMap.valMap."${status.uid}" = status.value
			}
			devList << idevMap
			state.deviceList =  devList
/*
			devMap."${device.id}" = [:] + device
			devMap."${device.id}".valMap = [:]
			debug("Found device", "${device.name}, total devices ${devMap.size()}")
			//state.deviceId = device.id
			state.deviceMap =  devMap */
		}
	}
	/*
	// Update state attributes
	responseJson['status']['status'].each { status ->
		devMap."${status.deviceId}".valMap."${status.uid}" = status.value
		state.deviceMap =  devMap
	}*/
	state.remove('deviceMap') // cleanup old version state

	def child
	devList.each { dev ->
		String t0 = (String)dev.id
		def tdev = getChildDevice(t0)
		if(tdev) {
			//log.warn "found device ${tdev}"
		} else {
			child = addChildDevice( 'imnotbob', 'IntesisHome HVAC', t0, null, [label: "${dev.name}"])
			debug(
					"handlePollResponse", "Created ${child.displayName} with id: " +
					"${child.id}, MAC: ${child.deviceNetworkId}"
			)
			pause(2000)
		}
	}
/*	devMap.each { dev ->
		String t0 =  "${dev.value.id}"
		def tdev = getChildDevice(t0)
		if(tdev) {
			//log.warn "found device ${tdev}"
		} else {
			child = addChildDevice( 'imnotbob', 'IntesisHome HVAC', t0, null, [label: "${dev.value.name}"])
			debug(
				"handlePollResponse", "Created ${child.displayName} with id: " +
				"${child.id}, MAC: ${child.deviceNetworkId}"
			)
			pause(2000)
		}
	} */

	def child1 = getTelnetDev()
	if(!child1) {
		child1 = addChildDevice( 'imnotbob', 'IntesisHome Telnet', 'IntTelnet', null, [label: "IntesisHome Telnet Driver"])
		debug(
			"handlePollResponse", "Created ${child1.displayName} with id: " +
			"${child1.id}, MAC: ${child1.deviceNetworkId}"
		)
		pause(2000)
	}

	devList.each { dev ->
		String t0 =  (String)dev.id
		def tdev = getChildDevice(t0)
		if(tdev) {
			tdev.generateEvent(dev)
		} else { debug "handlePollResponse", "child not found $t0" }
		state.lastRefresh=now()
	}
/*	devMap.each { dev ->
		def t0 =  "${dev.value.id}"
		def tdev = getChildDevice(t0)
		if(tdev) {
			tdev.generateEvent(dev.value)
		} else { debug "handlePollResponse", "child not found $t0" }
	} */

	if(child1) child1.connect()
	else error "handlePollResponse", "Telnet device not found"
}

void telnetUp() {
	debug("telnetUp", "")
	atomicState.telnet = true
}

void telnetDown(Boolean runPoll=false) {
	debug("telnetDown(${runPoll})", "")
	atomicState.telnet = false
	atomicState.server = null
	atomicState.serverPort = null
	atomicState.token = null
	if(runPoll) runIn(24, pollStatus)
	else if((Boolean)settings.enabled) runIn(900, pollStatus)
}

void queuePollStatus(Integer delay=3) {
	debug("queuePollStatus()", "")
	runIn(delay, pollStatus)
}

def getTelnetDev() {
	def tdev = app.getChildDevices()
	def myDev=null
	tdev.each { dev ->
		if(dev?.typeName in ['IntesisHome Telnet']) {
			myDev = dev
		}
	}
	myDev
}

void updateDeviceState(Long deviceId, Integer uid, Short value) {
	String t0 =  deviceId.toString()
	def tdev = app.getChildDevice(t0)
	if(tdev) tdev.updateDeviceState(deviceId, uid, value)
	else log.warn "no tdev $deviceId $uid  $value"
}

Map getParams() {
	return [server: atomicState.server, port: atomicState.serverPort, token: atomicState.token]
}

// --- "Constants" & Global variables
@Field static final String INTESIS_URL= "https://user.intesishome.com/api.php/get/control"
//static String getINTESIS_URL() { return "https://user.intesishome.com/api.php/get/control" }
//def getINTESIS_CMD_STATUS() { return '{"status":{"hash":"x"},"config":{"hash":"x"}}' }
//def getINTESIS_API_VER() { return "2.1" }

void sendMsg(String msg) {
	def tdev = getTelnetDev()
	if(tdev) tdev.sendMsg(msg)
	else log.warn "did not find telnet device"
}

void refresh() {
	if ((Boolean)settings.logEnable) log.debug "refresh"
}

private static String createLogString(String context, String message) {
	return "[IntesisHome Connect." + context + "] " + message
}

private void error(String context, String text, Exception e=null, Boolean remote=true) {
	log.error(createLogString(context, text) + e?.message)
}

private void debug(String context, String text, Boolean remote=true) {
	if ((Boolean)settings.logEnable) log.debug(createLogString(context, text))
}

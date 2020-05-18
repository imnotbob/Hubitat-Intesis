/**
 * IntesisHome Connect
 *
 * Author: ERS
 *       based off device work by Martin Blomgren
 * Last update: 2019-12-14
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

definition(
	name: 'IntesisHome Connect',
	namespace: 'imnotbob',
	author: 'ERS',
	
	description: 'Allows you to integrate your Intesis with Hubitat.',
	singleInstance: true,
	iconUrl: "",
	iconX2Url: "",
	iconX3Url: "",
//	importUrl: "https://raw.githubusercontent.com/tonesto7/nst-manager-he/master/apps/nstManager.groovy",
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

		if(username && password) {
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
	if (logEnable) runIn(1800,logsOff)
	if(enabled) pollStatus()
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
	if (logEnable) debug("pollStatus()", "")
	if(username && password && enabled) {
		def params = [
			uri	: INTESIS_URL,
			contentType: "application/x-www-form-urlencoded",
			body	: 'username=' + username + '&password=' + password + '&cmd={"status":{"hash":"x"},"config":{"hash":"x"}}&version=1.8.5'
		]

		try {
			asynchttpPost('handlePollResponse', params, data)
		} catch (e) {
			queuePollStatus(600)
			error("pollStatus", "Error polling", e)
		}
	} else { log.warn "missing settings $username $enabled" }
}

void handlePollResponse(response, data) {
	if (logEnable) debug("handlePollResponse()", "")
	def responseError = response.hasError()

	def responseJson
	if (!responseError) responseJson = parseJson(response.data)

	if (responseError || responseJson?.errorMessage) {
		queuePollStatus(900)
		debug("hasError", "$responseError")
		def responseErrorStatus = response.getStatus()
		debug("errorStatus", "$responseErrorStatus")
		def responseErrorData = response.getErrorData()
		debug("errorData", "$responseErrorData")
		def responseErrorMessage= response.getErrorMessage()
		debug("errorMessage", "$responseErrorMessage")
		debug("errorjsonData", "$responseJson")
		return
	}

	//if (logEnable) debug("responseJson", "$responseJson")

	def devMap = [:]
	def instMap = [:]
	atomicState.server = responseJson['config']['serverIP']
	atomicState.serverPort = responseJson['config']['serverPort']
	atomicState.token = responseJson['config']['token']

	responseJson['config']['inst'].each { installation ->

		instMap["${installation.name}"] = installation
		if (logEnable) debug("Found installation", "${installation.name} total installations: ${instMap.size()}")
		state.installationMap = instMap
		//log.warn "Installation: $installation"

		installation['devices'].each { device ->
			devMap["${device.id}"] = [:] + device
			devMap["${device.id}"].valMap = [:]
			if (logEnable) debug("Found device", "${device.name}, total devices ${devMap.size()}")
			//state.deviceId = device.id
			state.deviceMap =  devMap
		}
	}
	// Update state attributes
	responseJson['status']['status'].each { status ->
		devMap["${status.deviceId}"].valMap."${status.uid}" = status.value
		state.deviceMap =  devMap
	}
	def child
	devMap.each { dev ->
		def t0 =  "${dev.value.id}"
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
	}

	def child1 = getTelnetDev()
	if(!child1) {
		child1 = addChildDevice( 'imnotbob', 'IntesisHome Telnet', 'IntTelnet', null, [label: "IntesisHome Telnet Driver"])
		debug(
			"halePollResponse", "Created ${child1.displayName} with id: " +
			"${child1.id}, MAC: ${child1.deviceNetworkId}"
		)
		pause(2000)
	}

	devMap.each { dev ->
		def t0 =  "${dev.value.id}"
		def tdev = getChildDevice(t0)
		if(tdev) {
			tdev.generateEvent(dev.value)
		} else { debug "handlePollResponse", "child not found $t0" }
	}

	if(child1) child1.connect()
	else debug "handlePollResponse", "Telnet device not found"
}

void telnetUp() {
	if (logEnable) debug("telnetUp", "")
	atomicState.telnet = true
}

void telnetDown(boolean runPoll=false) {
	if (logEnable) debug("telnetDown(${runPoll})", "")
	atomicState.telnet = false
	atomicState.server = null
	atomicState.serverPort = null
	atomicState.token = null
	if(runPoll) runIn(24, pollStatus)
	else if(enabled) runIn(900, pollStatus)
}

void queuePollStatus(int delay=3) {
	if (logEnable) debug("queuePollStatus()", "")
	runIn(delay, pollStatus)
}

def getTelnetDev() {
	def tdev = app.getChildDevices()
	def myDev
	tdev.each { dev ->
		if(dev?.typeName in ['IntesisHome Telnet']) {
			myDev = dev
		}
	}
	myDev
}

void updateDeviceState(long deviceId, int uid, short value) {
	def t0 =  "${deviceId}"
	def tdev = app.getChildDevice(t0)
	if(tdev) tdev.updateDeviceState(deviceId, uid, value)
	else log.warn "no tdev $deviceId $uid  $value"
}

def getParams() {
	return [server: atomicState.server, port: atomicState.serverPort, token: atomicState.token]
}

// --- "Constants" & Global variables
def getINTESIS_URL() { return "https://user.intesishome.com/api.php/get/control" }
def getINTESIS_CMD_STATUS() { return '{"status":{"hash":"x"},"config":{"hash":"x"}}' }
def getINTESIS_API_VER() { return "2.1" }

def sendMsg(String msg) {
	def tdev = getTelnetDev()
	if(tdev) tdev.sendMsg(msg)
	else log.warn "did not find telnet device"
}

void refresh() {
	if (logEnable) log.debug "refresh"
}

private String createLogString(String context, String message) {
	return "[IntesisHome Connect." + context + "] " + message
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

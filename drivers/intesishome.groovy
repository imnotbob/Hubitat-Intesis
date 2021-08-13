/**
 *  Intesis HVAC 0.1
 *
 * Author: ERS
 *       based off device work by Martin Blomgren
 * Last update: 2020-05-19
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

import groovy.transform.Field
import groovy.json.JsonSlurper
import hubitat.helper.InterfaceUtils

metadata {
	definition (name: "IntesisHome HVAC", namespace: 'imnotbob', author: "ERS") {
		capability "Configuration"
		capability "Refresh"

		capability "Actuator"

		capability "FanControl"
//		capability "Relative Humidity Measurement"
		capability "Temperature Measurement"
		capability "Sensor"

		capability "Energy Meter"
		capability "Power Meter"

		capability "Thermostat"

//		capability "Switch"

		//attribute "swing", "string"
		//attribute "temperatureUnit","string"
		attribute "outdoorTemperature", "number"
//		attribute "latestMode", "string"
		attribute "iFanSpeed", "string"
		attribute "ivvane", "string"
		attribute "ihvvane", "string"

		command "dry"
		command "on"

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

// --- "Constants" & Global variables
static String getINTESIS_URL() { return "https://user.intesishome.com/api.php/get/control" }

static String getINTESIS_CMD_STATUS() { return '{"status":{"hash":"x"},"config":{"hash":"x"}}' }
static String getINTESIS_API_VER() { return "2.1" }
static String getAPI_DISCONNECTED() { return "Disconnected" }
static String getAPI_CONNECTING() { return "Connecting" }
static String getAPI_AUTHENTICATED() { return "Connected" }
static String getAPI_AUTH_FAILED() { return "Wrong username/password" }

static Map getINTESIS_MAP() {
	String map = """
	{
	"1": {"name": "power", "values": {"0": "off", "1": "on"}},
	"2": {"name": "mode", "values": {"0": "auto", "1": "heat", "2": "dry", "3": "fan", "4": "cool"}},
	"4": {"name": "fan_speed", "values": {"0": "auto", "1": "quiet", "2": "low", "3": "medium", "4": "high"}},
	"5": {"name": "vvane", "values": {"0": "auto/stop", "10": "swing", "1": "manual1", "2": "manual2", "3": "manual3", "4": "manual4", "5": "manual5"}},
	"6": {"name": "hvane", "values": {"0": "auto/stop", "10": "swing", "1": "manual1", "2": "manual2", "3": "manual3", "4": "manual4", "5": "manual5"}},
	"9": {"name": "setpoint", "null": 32768},
	"10": {"name": "temperature"},
	"13": {"name": "working_hours"},
	"35": {"name": "setpoint_min"},
	"36": {"name": "setpoint_max"},
	"37": {"name": "outdoor_temperature"},
	"68": {"name": "current_power_consumption"},
	"69": {"name": "total_power_consumption"},
	"70": {"name": "weekly_power_consumption"}
	}
	"""
/* """ */
	return (Map) new JsonSlurper().parseText(map)
}

static Map getCOMMAND_MAP() {
	String cmd = """
	{
	"power": {"uid": 1, "values": {"off": 0, "on": 1}},
	"mode": {"uid": 2, "values": {"auto": 0, "heat": 1, "dry": 2, "fan": 3, "cool": 4}},
	"fan_speed": {"uid": 4, "values": {"auto": 0, "quiet": 1, "low": 2, "medium": 3, "high": 4}},
	"vvane": {"uid": 5, "values": {"auto/stop": 0, "swing": 10, "manual1": 1, "manual2": 2, "manual3": 3, "manual4": 4, "manual5": 5}},
	"hvane": {"uid": 6, "values": {"auto/stop": 0, "swing": 10, "manual1": 1, "manual2": 2, "manual3": 3, "manual4": 4, "manual5": 5}},
	"setpoint": {"uid": 9}
	}
	"""
	return (Map) new JsonSlurper().parseText(cmd)
}

void initialize() {
	debug "initialize", ""
	setModes()
}

void installed() {
	String tempscale = getTemperatureScale()
	def tz = location.timeZone
	if(!tz || !(tempscale == "F" || tempscale == "C")) {
		log.warn "Timezone (${tz}) or Temperature Scale (${tempscale}) not set"
	}
// set some dummy values, for google integration
	if(tempscale=='F') {
		sendEvent(name:"coolingSetpoint", value:80)
	}else{
		sendEvent(name:"coolingSetpoint", value:28)
	}
	initialize()
}

void logsOff() {
	debug "logsOff", "text logging disabled..."
	debug "logsOff", "debug logging disabled..."
	device.updateSetting("logEnable",[value:"false",type:"bool"])
	device.updateSetting("txtEnable",[value:"false",type:"bool"])
}

void updated() {
	debug "updated", "debug logging is: ${logEnable == true}"
	debug "updated", "description logging is: ${txtEnable == true}"
	if (logEnable) runIn(1800,logsOff)

	initialize()
}

void setModes() {
	// supported in device "auto", "heat", "dry", "fan", "cool"
	def supportedThermostatModes = ["off", "auto", "heat", "cool"]  // HE capabilities (no "emerency heat")
	// supported in device "auto", "quiet", "low", "medium", "high"
	def supportedFanModes = ["auto", "on", "circulate"]  // HE capabilities
	sendEvent(name: "supportedThermostatModes", value: supportedThermostatModes, displayed: false )
	sendEvent(name: "supportedThermostatFanModes", value: supportedFanModes, displayed: false)
// not allowed
	//def supportedThermostatModes = ["off", "auto", "heat", "dry", "fan", "cool"]
	//def supportedFanModes = ["auto", "quiet", "low", "medium", "high"]
}

void generateEvent(tData) {
	Long myId = "${tData.id}".toLong()
	state.deviceId = myId
	tData.valMap.each { val ->
		updateDeviceState(myId, val.key.toInteger(), (Short)val.value)
	}
}

void updateDeviceState(Long deviceId, Integer uid, Short value) {
	if (uid == 60002) return
//	if (logEnable) log.debug "[IntesisHome.thermostat] updateDeviceState: deviceId=${deviceId}, uid=${uid}, value=${value}"

	String sUid = uid.toString()
	Map myINTESIS_MAP = INTESIS_MAP
	if (myINTESIS_MAP.containsKey(sUid)) {

		if (myINTESIS_MAP[sUid].containsKey('values')) { // power, mode, fan_speed, vvane, hvane
			String valuesValue = myINTESIS_MAP[sUid].values[value.toString()]

			switch ((String)myINTESIS_MAP[sUid].name) {
				case "power": // off, on
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState power: $valuesValue"
					if (valuesValue == "off") {
						state.mpower = false
						sendEvent(name: "thermostatMode", value: valuesValue)
						//sendEvent(name: "switch", value: "off")
						sendEvent(name: "thermostatOperatingState", value: "idle")
					} else if (valuesValue == "on") {
						if((Boolean)state.mpower == false && (Boolean)state.mpower != null) {
							state.mpower = true // if we transition off -> on, force re-update of variables
							//sendEvent(name: "switch", value: "on")
							parent.queuePollStatus()
							return
						}
//						state.mpower = true
//						sendEvent(name: "thermostatMode", value: device.currentValue("latestMode", true))
//						updateOperatingState()
					}
					break

				case "mode": // auto, heat, dry, fan, cool
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState mode: $valuesValue"
					// thermostatMode - auto, heat, cool, 'off', 'emergency heat'
					String myVal = valuesValue
					//state.lastMode = valuesValue //sendEvent(name: "latestMode", value: valuesValue)
					if(myVal!='off')state.lastMode = myVal
					if((Boolean)state.mpower) {
						state.curMode=myVal
						if(myVal == 'dry')  myVal = 'cool'
						else if(myVal == 'fan') {
							myVal = 'off'
							sendEvent(name: "thermostatFanMode", value: 'on')
						}
						sendEvent(name: "thermostatMode", value: myVal)
						//if(myVal!='off')state.lastMode = myVal
						updateOperatingState()
					} else {
						myVal = 'off'
						state.curMode=myVal
						sendEvent(name: "thermostatMode", value: myVal)
						sendEvent(name: "thermostatOperatingState", value: "idle")
					}
					break

				case "fan_speed": // auto, quiet, low, medium, high
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState fan_speed: $valuesValue"
					//if (!state.mpower) sendEvent(name: "thermostatFanMode", value: 'auto')
					//else
					sendEvent(name: "thermostatFanMode", value: valuesValue != 'auto' ? 'on' : 'auto')
					sendEvent(name: "speed", value: valuesValue)
					sendEvent(name: "iFanSpeed", value: valuesValue)
					break

				case "vvane": // auto/stop, swing, manual1, manual2, manual3, manual4, manual5
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState vvane: $valuesValue"
					sendEvent(name: "ivvane", value: valuesValue)
					break

				case "hvane": // auto/stop, swing, manual1, manual2, manual3, manual4, manual5
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState hvane: $valuesValue"
					sendEvent(name: "ihvvane", value: valuesValue)
					break

				default:
					if (logEnable) log.info "[IntesisHome.thermostat] updateDeviceState values uid NOT FOUND"
					break
			}


		} else if (myINTESIS_MAP[sUid].containsKey('null') && value == myINTESIS_MAP[sUid].null) {
			//setPointTemperature should be set to none...

		} else {
			def tempVal = getTemperatureScale() == 'C' ? value/10 : Math.round(( (value/10.0) * (9.0/5.0) + 32.0) )
			String myUnit = "\u00b0${getTemperatureScale()}"
			switch ((String)myINTESIS_MAP[sUid].name) {
				case "setpoint":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState setpoint: ${value/10}"
					sendEvent(name: "thermostatSetpoint", value: tempVal, unit: myUnit)
					def cVal = tempVal
					def hVal = tempVal //t1
					String t0=(String)state.curMode
					//String t0 = device.currentValue("latestMode", true)
					//if (t0 == "heat") { cVal = 0 }
					//if (t0 == "cool") { hVal = 0 }
					if (t0 == "heat") sendEvent(name: "heatingSetpoint", value: hVal, unit: myUnit)
					if (t0 == "cool") sendEvent(name: "coolingSetpoint", value: cVal, unit: myUnit)
					//sendEvent(name: "coolingSetpoint", value: cVal, unit: myUnit)
					//sendEvent(name: "heatingSetpoint", value: hVal, unit: myUnit)
					break

				case "temperature":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState temperature: ${value/10}"
					sendEvent(name: "temperature", value: tempVal, unit: myUnit)
					break

				case "working_hours":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState working_hours: $value"
					//sendEvent(name: "ThermostatSetpoint", value: value/)
					break

				case "setpoint_min":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState setpoint_min: ${value/10}"
					//sendEvent(name: "ThermostatSetpoint", value: value/10)
					break

				case "setpoint_max":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState setpoint_max: ${value/10}"
					//sendEvent(name: "ThermostatSetpoint", value: value/10)
					break

				case "outdoor_temperature":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState outdoor_temperature: ${value/10}"
					sendEvent(name: "outdoorTemperature", value: tempVal, unit: myUnit)
					break

				case "current_power_consumption":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState current_power_consumption: $value"
					sendEvent(name: "power", value: value)

					// thermostatMode - auto, heat, dry, fan, cool
					// thermostatOperatingState - ENUM ["vent economizer", "pending cool", "cooling", "heating", "pending heat", "fan only", "idle"]
					if (value < 20 || !(Boolean)state.mpower) {
						sendEvent(name: "thermostatOperatingState", value: "idle")
					} else if ((Boolean)state.mpower) updateOperatingState()
					break

				case "total_power_consumption":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState total_power_consumption: $value"
					sendEvent(name: "energy", value: value)
					break

				case "weekly_power_consumption":
					if (txtEnable) log.info "[IntesisHome.thermostat] updateDeviceState weekly_power_consumption: $value"
					//sendEvent(name: "ThermostatSetpoint", value: value/10)
					break

				default:
					if (logEnable) log.debug "[IntesisHome.thermostat] updateDeviceState non-values uid NOT FOUND"
					break
			}
		}
	}
}

void updateOperatingState() {
	if(!(Boolean)state.mpower) return
	String t0=(String)state.curMode
	//String t0 = device.currentValue("latestMode", true)
//off is handled elsewhere
	if (t0 == "auto") {
		sendEvent(name: "thermostatOperatingState", value: "heating")
		//sendEvent(name: "thermostatOperatingState", value: "")

	} else if (t0 == "heat") {
		sendEvent(name: "thermostatOperatingState", value: "heating")

	} else if (t0 == "dry") {
		sendEvent(name: "thermostatOperatingState", value: "vent economizer")

	} else if (t0 == "fan") {
		sendEvent(name: "thermostatOperatingState", value: "fan only")

	} else if (t0 == "cool") {
		sendEvent(name: "thermostatOperatingState", value: "cooling")

	}
}

void setPointAdjust(Double value) {
	Integer intVal = (Integer) (getTemperatureScale() == 'C' ? Math.round(value * 10) : Math.round(((value - 32.0) * (5.0 / 9.0)) * 10.0))
	String myUnit = "\u00b0${getTemperatureScale()}"
	if (txtEnable) log.info "[IntesisHome.thermostat] setPointAdjust to: $intVal  from $value $myUnit"

	//def uid = 9
	Integer uid = COMMAND_MAP['setpoint']['uid'] as Integer
	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + intVal + ',"seqNo":0}}'

	parent.sendMsg(message)
}

void setHeatingSetpoint(Double value) {
	if (txtEnable) log.info "[IntesisHome.thermostat] setHeatingSetpoint to: $value"
	setPointAdjust(value)
}

void setCoolingSetpoint(Double value) {
	if (txtEnable) log.info "[IntesisHome.thermostat] setCoolingSetpoint to: $value"
	setPointAdjust(value)
}

void setThermostatMode(String mode) {
	if (txtEnable) log.info "[IntesisHome.thermostat] setThermostatMode to: $mode"
	//supportedThermostatModes : [off, auto, heat, dry, fan, cool]
	if(mode == 'off') {
		setPower('off')
	}
	if(mode == 'emergency heat') mode = 'heat'
	Integer uid = COMMAND_MAP['mode']['uid'] as Integer
	Integer value = COMMAND_MAP['mode'].values[mode] as Integer

	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + value + ',"seqNo":0}}'
	if (logEnable) log.debug "[IntesisHome.thermostat] send message: $message"
	parent.sendMsg(message)
}

void setThermostatFanMode(String mode) {
	if (txtEnable) log.info "[IntesisHome.thermostat] setThermostatFanMode to: $mode"
	//supportedThermostatFanModes : [auto, quiet, low, medium, high]
	if(mode=='on' || mode=='circulate') { fanOn(); return }
	Integer uid = COMMAND_MAP['fan_speed']['uid'] as Integer
	Integer value = COMMAND_MAP['fan_speed'].values[mode] as Integer

	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + value + ',"seqNo":0}}'
	if (logEnable) log.debug "[IntesisHome.thermostat] send message: $message"
	parent.sendMsg(message)
}

void setSpeed(String fanspeed) {
	if (txtEnable) log.info "[IntesisHome.thermostat] setSpeed to: $fanspeed"
	if(!(Boolean)state.mpower) {
		setPower('on')
		setThermostatMode('fan')
	}
	switch(fanspeed) {
		case ['low','low-medium']:
			setThermostatFanMode("low")
			break
		case ['medium','medium-high']:
			setThermostatFanMode("medium")
			break
		case 'high':
			setThermostatFanMode("high")
			break
		case 'on':
			setThermostatFanMode("on")
			break
		case ['auto','off']:
			setThermostatFanMode("auto")
			break
		default:
			log.warn "setSpeed: unknown speed"
	}
}

void setPower(String mode) {
	if (txtEnable) log.info "[IntesisHome.thermostat] setPower to: $mode"
	//supports : [off, on]
	Integer uid = COMMAND_MAP['power']['uid'] as Integer
	Integer value = COMMAND_MAP['power'].values[mode] as Integer

	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + value + ',"seqNo":0}}'
	if (logEnable) log.debug "[IntesisHome.thermostat] send message: $message"
	parent.sendMsg(message)
}

/* thermostat mode commands */
void cool() {
	if(!(Boolean)state.mpower) setPower('on')
	setThermostatMode('cool')
}

void heat() {
	if(!(Boolean)state.mpower) setPower('on')
	setThermostatMode('heat')
}

void auto() {
	if(!(Boolean)state.mpower) setPower('on')
	setThermostatMode('auto')
}

void emergencyHeat() {
	if(!(Boolean)state.mpower) setPower('on')
	setThermostatMode('heat')
}

void dry() { // custom command
	if(!(Boolean)state.mpower) setPower('on')
	setThermostatMode('dry')
}

void on() { // custom command
	setPower('on')
}

void off() {
	setPower('off')
}

/* Fan commands */
void fanOn() {
	setThermostatFanMode('low')
}

void fanAuto() {
	setThermostatFanMode('auto')
}

void fanCirculate() {
	setThermostatFanMode('low')
}

def setValue() {}

void refresh() {
	if (logEnable) log.debug "refresh"
	parent.queuePollStatus()
}

void configure() {
	if (txtEnable) log.debug "Configuring Reporting and Bindings."
	initialize()
}

private static String createLogString(String context, String message) {
	return "[IntesisHome.thermostat." + context + "] " + message
}

private void error(String context, String text, Exception e) {
	error(context, text, e, true)
}

private void error(String context, String text, Exception e, Boolean remote) {
	log.error(createLogString(context, text), e)
}

private void debug(String context, String text) {
	debug(context, text, true)
}

private void debug(String context, String text, Boolean remote) {
	log.debug(createLogString(context, text))
}

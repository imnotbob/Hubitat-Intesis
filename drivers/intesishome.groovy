/**
 *  Intesis HVAC 0.2
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
static String version() {"v0.2"}

import groovy.transform.Field
import groovy.json.JsonSlurper
import hubitat.helper.InterfaceUtils

//@Field static final String sNULL=(String)null
@Field static final String sSNULL='null'
@Field static final String sON='on'
@Field static final String sOFF='off'
@Field static final String sTHERMOS='thermostatOperatingState'
@Field static final String sUID='uid'
@Field static final String sMODE='mode'
@Field static final String sPWR='power'
@Field static final String sCOOL='cool'
@Field static final String sHEAT='heat'
@Field static final String sAUTO='auto'
@Field static final String sLOW='low'
@Field static final String sFANSPD='fan_speed'

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
//static String getINTESIS_URL() { return "https://user.intesishome.com/api.php/get/control" }

//static String getINTESIS_CMD_STATUS() { return '{"status":{"hash":"x"},"config":{"hash":"x"}}' }
//static String getINTESIS_API_VER() { return "2.1" }
//static String getAPI_DISCONNECTED() { return "Disconnected" }
//static String getAPI_CONNECTING() { return "Connecting" }
//static String getAPI_AUTHENTICATED() { return "Connected" }
//static String getAPI_AUTH_FAILED() { return "Wrong username/password" }

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
	debug("initialize", "")
	setModes()
}

void installed() {
	String tempscale = getTemperatureScale()
	TimeZone tz = (TimeZone)location.timeZone
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
	debug("updated", "debug logging is: ${(Boolean)settings.logEnable == true}")
	debug("updated", "description logging is: ${(Boolean)settings.txtEnable == true}")
	if ((Boolean)settings.logEnable) runIn(1800,logsOff)

	initialize()
}

void setModes() {
	// supported in device "auto", "heat", "dry", "fan", "cool"
	List<String> supportedThermostatModes = [sOFF, sAUTO, sHEAT, sCOOL]  // HE capabilities (no "emerency heat")
	// supported in device "auto", "quiet", "low", "medium", "high"
	List<String> supportedFanModes = [sAUTO, sON, "circulate"]  // HE capabilities
	sendEvent(name: "supportedThermostatModes", value: supportedThermostatModes, displayed: false )
	sendEvent(name: "supportedThermostatFanModes", value: supportedFanModes, displayed: false)
// not allowed
	//def supportedThermostatModes = [sOFF, "auto", "heat", "dry", "fan", "cool"]
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
	String msgH = "updateDeviceState"
//	debug(msgH,"deviceId=${deviceId}, uid=${uid}, value=${value}")

	String sUid = uid.toString()
	Map myINTESIS_MAP = INTESIS_MAP
	if (myINTESIS_MAP.containsKey(sUid)) {

		if (myINTESIS_MAP[sUid].containsKey('values')) { // power, mode, fan_speed, vvane, hvane
			String valuesValue = myINTESIS_MAP[sUid].values[value.toString()]
			String sName = (String)myINTESIS_MAP[sUid].name
			switch (sName) {
				case "power": // off, on
					info(msgH,"power: $valuesValue")
					if (valuesValue == sOFF) {
						state.mpower = false
						sendEvent(name: "thermostatMode", value: valuesValue)
						//sendEvent(name: "switch", value: sOFF)
						sendEvent(name: sTHERMOS, value: "idle")
					} else if (valuesValue == sON) {
						if((Boolean)state.mpower == false && (Boolean)state.mpower != null) {
							state.mpower = true // if we transition off -> on, force re-update of variables
							//sendEvent(name: "switch", value: sON)
							parent.queuePollStatus()
							return
						}
//						state.mpower = true
//						sendEvent(name: "thermostatMode", value: device.currentValue("latestMode", true))
//						updateOperatingState()
					}
					break

				case sMODE: // auto, heat, dry, fan, cool
					info(msgH,"mode: $valuesValue")
					// thermostatMode - auto, heat, cool, sOFF, 'emergency heat'
					String myVal = valuesValue
					if(myVal!=sOFF)state.lastMode = myVal
					if((Boolean)state.mpower) {
						state.curMode=myVal
						if(myVal == 'dry')  myVal = sCOOL
						else if(myVal == 'fan') {
							myVal = sOFF
							sendEvent(name: "thermostatFanMode", value: sON)
						}
						sendEvent(name: "thermostatMode", value: myVal)
						updateOperatingState()
					} else {
						myVal = sOFF
						state.curMode=myVal
						sendEvent(name: "thermostatMode", value: myVal)
						sendEvent(name: sTHERMOS, value: "idle")
					}
					break

				case sFANSPD: // auto, quiet, low, medium, high
					info(msgH,"fan_speed: $valuesValue")
					//if (!state.mpower) sendEvent(name: "thermostatFanMode", value: sAUTO)
					//else
					sendEvent(name: "thermostatFanMode", value: valuesValue != sAUTO ? sON : sAUTO)
					sendEvent(name: "speed", value: valuesValue)
					sendEvent(name: "iFanSpeed", value: valuesValue)
					break

				case "vvane": // auto/stop, swing, manual1, manual2, manual3, manual4, manual5
					info(msgH,"vvane: $valuesValue")
					sendEvent(name: "ivvane", value: valuesValue)
					break

				case "hvane": // auto/stop, swing, manual1, manual2, manual3, manual4, manual5
					info(msgH,"hvane: $valuesValue")
					sendEvent(name: "ihvvane", value: valuesValue)
					break

				default:
					info(msgH,"values uid NOT FOUND")
					break
			}


		} else if (myINTESIS_MAP[sUid].containsKey(sSNULL) && value == myINTESIS_MAP[sUid].null) {
			//setPointTemperature should be set to none...

		} else {
			def tempVal = getTemperatureScale() == 'C' ? value/10 : Math.round(( (value/10.0) * (9.0/5.0) + 32.0) )
			String myUnit = "\u00b0${getTemperatureScale()}"
			String sName = (String)myINTESIS_MAP[sUid].name
			switch (sName) {
				case "setpoint":
					info(msgH,"setpoint: ${value/10}")
					sendEvent(name: "thermostatSetpoint", value: tempVal, unit: myUnit)
					def cVal = tempVal
					def hVal = tempVal //t1
					String t0=(String)state.curMode
					if (t0 == sHEAT) sendEvent(name: "heatingSetpoint", value: hVal, unit: myUnit)
					if (t0 == sCOOL) sendEvent(name: "coolingSetpoint", value: cVal, unit: myUnit)
					break

				case "temperature":
					info(msgH,"temperature: ${value/10}")
					sendEvent(name: "temperature", value: tempVal, unit: myUnit)
					break

				case "working_hours":
					info(msgH,"working_hours: $value")
					//sendEvent(name: "ThermostatSetpoint", value: value)
					break

				case "setpoint_min":
					info(msgH,"setpoint_min: ${value/10}")
					//sendEvent(name: "ThermostatSetpoint", value: value/10)
					break

				case "setpoint_max":
					info(msgH,"setpoint_max: ${value/10}")
					//sendEvent(name: "ThermostatSetpoint", value: value/10)
					break

				case "outdoor_temperature":
					info(msgH,"outdoor_temperature: ${value/10}")
					sendEvent(name: "outdoorTemperature", value: tempVal, unit: myUnit)
					break

				case "current_power_consumption":
					info(msgH,"current_power_consumption: $value")
					sendEvent(name: "power", value: value)

					// thermostatMode - auto, heat, dry, fan, cool
					// thermostatOperatingState - ENUM ["vent economizer", "pending cool", "cooling", "heating", "pending heat", "fan only", "idle"]
					if (value < 20 || !(Boolean)state.mpower) {
						sendEvent(name: sTHERMOS, value: "idle")
					} else if ((Boolean)state.mpower) updateOperatingState()
					break

				case "total_power_consumption":
					info(msgH,"total_power_consumption: $value")
					sendEvent(name: "energy", value: value)
					break

				case "weekly_power_consumption":
					info(msgH,"weekly_power_consumption: $value")
					//sendEvent(name: "ThermostatSetpoint", value: value/10)
					break

				default:
					debug(msgH,"non-values uid NOT FOUND")
					break
			}
		}
	}
}

void updateOperatingState() {
	if(!(Boolean)state.mpower) return
	String t0=(String)state.curMode
//off is handled elsewhere
	switch(t0) {
		case sAUTO:
			sendEvent(name: sTHERMOS, value: "heating")
			break
		case sHEAT:
			sendEvent(name: sTHERMOS, value: "heating")
			break
		case sCOOL:
			sendEvent(name: sTHERMOS, value: "cooling")
			break
		case "fan":
			sendEvent(name: sTHERMOS, value: "fan only")
			break
		case "dry":
			sendEvent(name: sTHERMOS, value: "vent economizer")
			break
	}
}

void setPointAdjust(Double value) {
	Integer intVal = (Integer) (getTemperatureScale() == 'C' ? Math.round(value * 10) : Math.round(((value - 32.0) * (5.0 / 9.0)) * 10.0))
	String myUnit = "\u00b0${getTemperatureScale()}"
	info("setPointAdjust","to: $intVal  from $value $myUnit")

	//def uid = 9
	Integer uid = COMMAND_MAP['setpoint'][sUID] as Integer
	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + intVal + ',"seqNo":0}}'

	parent.sendMsg(message)
}

void setHeatingSetpoint(Double value) {
	info("setHeatingSetpoint","to: $value")
	setPointAdjust(value)
}

void setCoolingSetpoint(Double value) {
	info("setCoolingSetpoint","to: $value")
	setPointAdjust(value)
}

void setThermostatMode(String mode) {
	info("setThermostatMode","to: $mode")
	//supportedThermostatModes : [off, auto, heat, dry, fan, cool]
	if(mode == sOFF) {
		setPower(sOFF)
	}
	if(mode == 'emergency heat') mode = sHEAT
	Integer uid = COMMAND_MAP[sMODE][sUID] as Integer
	Integer value = COMMAND_MAP[sMODE].values[mode] as Integer

	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + value + ',"seqNo":0}}'
	debug("setThermostatMode", "sending command message: $message")
	parent.sendMsg(message)
}

void setThermostatFanMode(String mode) {
	info("setThermostatFanMode","to: $mode")
	//supportedThermostatFanModes : [auto, quiet, low, medium, high]
	if(mode==sON || mode=='circulate') { fanOn(); return }
	Integer uid = COMMAND_MAP[sFANSPD][sUID] as Integer
	Integer value = COMMAND_MAP[sFANSPD].values[mode] as Integer

	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + value + ',"seqNo":0}}'
	debug("setThermostatFanMode", "sending command message: $message")
	parent.sendMsg(message)
}

void setSpeed(String fanspeed) {
	info("setSpeed","to: $fanspeed")
	if(!(Boolean)state.mpower) {
		setPower(sON)
		setThermostatMode('fan')
	}
	switch(fanspeed) {
		case [sLOW,'low-medium']:
			setThermostatFanMode(sLOW)
			break
		case ['medium','medium-high']:
			setThermostatFanMode("medium")
			break
		case 'high':
			setThermostatFanMode("high")
			break
		case sON:
			setThermostatFanMode(sON)
			break
		case [sAUTO,sOFF]:
			setThermostatFanMode(sAUTO)
			break
		default:
			log.warn "setSpeed: unknown speed"
	}
}

void setPower(String mode) {
	info("setPower","to: $mode")
	//supports : [off, on]
	Integer uid = COMMAND_MAP[sPWR][sUID] as Integer
	Integer value = COMMAND_MAP[sPWR].values[mode] as Integer

	String message = '{"command":"set","data":{"deviceId":' + (Long)state.deviceId + ',"uid":' + uid + ',"value":' + value + ',"seqNo":0}}'
	debug("setPower", "sending command message: $message")
	parent.sendMsg(message)
}

/* thermostat mode commands */
void cool() {
	if(!(Boolean)state.mpower) setPower(sON)
	setThermostatMode(sCOOL)
}

void heat() {
	if(!(Boolean)state.mpower) setPower(sON)
	setThermostatMode(sHEAT)
}

void auto() {
	if(!(Boolean)state.mpower) setPower(sON)
	setThermostatMode(sAUTO)
}

void emergencyHeat() {
	if(!(Boolean)state.mpower) setPower(sON)
	setThermostatMode(sHEAT)
}

void dry() { // custom command
	if(!(Boolean)state.mpower) setPower(sON)
	setThermostatMode('dry')
}

void on() { // custom command
	setPower(sON)
}

void off() {
	setPower(sOFF)
}

/* Fan commands */
void fanOn() {
	setThermostatFanMode(sLOW)
}

void fanAuto() {
	setThermostatFanMode(sAUTO)
}

void fanCirculate() {
	setThermostatFanMode(sLOW)
}

def setValue() {}

void refresh() {
	debug("refresh", "")
	parent.queuePollStatus()
}

void configure() {
	info("configure", "Configuring Reporting and Bindings.")
	initialize()
}

private static String createLogString(String context, String message) {
	return "[IntesisHome.thermostat." + context + "] " + message
}

private void error(String context, String text, Exception e=null, Boolean remote=true) {
	log.error(createLogString(context, text)+ e?.toString())
}

private void debug(String context, String text, Boolean remote=true) {
	if ((Boolean)settings.logEnable) log.debug(createLogString(context, text))
}

private void info(String context, String text, Boolean remote=true) {
	if ((Boolean)settings.txtEnable) log.info(createLogString(context, text))
}

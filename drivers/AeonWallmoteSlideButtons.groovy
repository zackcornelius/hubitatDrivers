 /**
 *  Copyright 2016 Eric Maycock
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Aeon WallMote Dual/Quad
 *
 *  Author: Eric Maycock (erocm123)
 *  Date: 2017-06-19
 *
 *  2017-06-19: Added check to only send color change config for three wakeups. Editing preferences
 *              and hitting "done" will reset the counter. This is an attempt to prevent freezing
 *              caused by updating preferences.
 */
 
metadata {
	definition (name: "Aeon WallMote", namespace: "erocm123", author: "Eric Maycock", vid:"generic-button") {
		capability "Actuator"
		capability "Configuration"
		capability "Sensor"
        capability "Battery"
        capability "Health Check"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        
        attribute "sequenceNumber", "number"
        attribute "numberOfButtons", "number"
        attribute "needUpdate", "string"
        attribute "pushed", "number"
        attribute "held", "number"
        attribute "released", "number"
        
        fingerprint mfr: "0086", prod: "0102", model: "0082", deviceJoinName: "Aeon WallMote"

		fingerprint deviceId: "0x1801", inClusters: "0x5E,0x73,0x98,0x86,0x85,0x59,0x8E,0x60,0x72,0x5A,0x84,0x5B,0x71,0x70,0x80,0x7A", outClusters: "0x25,0x26" // secure inclusion
        fingerprint deviceId: "0x1801", inClusters: "0x5E,0x85,0x59,0x8E,0x60,0x86,0x70,0x72,0x5A,0x73,0x84,0x80,0x5B,0x71,0x7A", outClusters: "0x25,0x26"
        
	}
    preferences {
        input name: "enableTouchBeep", type: "bool", title: "Enable Touch beep", defaultValue: true
        input name: "enableTouchVibration", type: "bool", title: "Enable Touch vibration", defaultValue: true
        input name: "enableSlide", type: "bool", title: "Enable slide button events", defaultValue: true
        input name: "wallmoteButtons", type: "enum", title: "Wallmote button count", options: [[2:"2"],[4:"4"]], defaultValue: 4, required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def parse(String description) {
	def results = []
	if (description.startsWith("Err")) {
	    results = createEvent(descriptionText:description, displayed:true)
	} else {
		def cmd = zwave.parse(description, [0x2B: 1, 0x80: 1, 0x84: 1])
		if(cmd) results += zwaveEvent(cmd)
		if(!results) results = [ descriptionText: cmd, displayed: false ]
	}
    
    logging(results)
	return results
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelStartLevelChange cmd) {
        logging("upDown: $cmd.upDown")
        
        switch (cmd.upDown) {
           case 0: // Up
              buttonEvent(device.currentValue("numberOfButtons"), "pushed")
           break
           case 1: // Down
              buttonEvent(device.currentValue("numberOfButtons"), "held")
           break
           default:
              logging("Unhandled SwitchMultilevelStartLevelChange: ${cmd}")
           break
        }
}

def zwaveEvent(hubitat.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
        logging("keyAttributes: $cmd.keyAttributes")
        logging("sceneNumber: $cmd.sceneNumber")
        logging("sequenceNumber: $cmd.sequenceNumber")

        sendEvent(name: "sequenceNumber", value: cmd.sequenceNumber, displayed:false)
        switch (cmd.keyAttributes) {
           case 0:
              buttonEvent(cmd.sceneNumber, "pushed")
           break
           case 1: // released
              buttonEvent(cmd.sceneNumber, "released")
           break
           case 2: // held
              buttonEvent(cmd.sceneNumber, "held")
           break
           default:
              logging("Unhandled CentralSceneNotification: ${cmd}")
           break
        }
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapsulatedCommand = cmd.encapsulatedCommand([0x5B: 1, 0x20: 1, 0x31: 5, 0x30: 2, 0x84: 1, 0x70: 1])
	state.sec = 1
	if (encapsulatedCommand) {
		zwaveEvent(encapsulatedCommand)
	} else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
		createEvent(descriptionText: cmd.toString())
	}
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityCommandsSupportedReport cmd) {
	response(configure())
}

def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpIntervalReport cmd)
{
	logging("WakeUpIntervalReport ${cmd.toString()}")
    state.wakeInterval = cmd.seconds
}

def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpNotification cmd)
{
    List<hubitat.zwave.Command> cmds = []
    logging("Device ${device.displayName} woke up")

    cmds.add(zwave.versionV1.versionGet())
    if (!state.lastBatteryReport || (now() - state.lastBatteryReport) / 60000 >= 60 * 24)
    {
        logging("Over 24hr since last battery report. Requesting report")
        cmds.add(zwave.batteryV1.batteryGet())
    }
    logging(state.desiredProperties)
    if (!state.currentProperties.containsKey("1") || cmd2Integer(state.currentProperties."1") != (enableTouchBeep ? 1 : 0)) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 1, size: 1, scaledConfigurationValue: (enableTouchBeep ? 1 : 0)))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 1))
    if (!state.currentProperties.containsKey("2") || cmd2Integer(state.currentProperties."2") != (enableTouchVibration ? 1 : 0)) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, scaledConfigurationValue: (enableTouchVibration ? 1 : 0)))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 2))
    if (!state.currentProperties.containsKey("3") || cmd2Integer(state.currentProperties."3") != (enableSlide ? 1 : 0)) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, scaledConfigurationValue: (enableSlide ? 1 : 0)))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 3))
    if (!state.currentProperties.containsKey("4") || cmd2Integer(state.currentProperties."4") != 3) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 4, size: 1, scaledConfigurationValue: 3))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 4))
    cmds.add(zwave.wakeUpV1.wakeUpNoMoreInformation())
    
    logging("Sending commands")
    logging(cmds)
    return response(commands(cmds))
}

def buttonEvent(button, value) {
	sendEvent(name: value, value: button, descriptionText: "$device.displayName button $button was $value", isStateChange: true)
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    logging("Battery Report: $cmd")
	def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF) {
		map.value = 1
		map.descriptionText = "${device.displayName} battery is low"
		map.isStateChange = true
	} else {
		map.value = cmd.batteryLevel
	}
	state.lastBatteryReport = now()
	createEvent(map)
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
	logging("AssociationReport $cmd")
    state."association${cmd.groupingIdentifier}" = cmd.nodeId[0]
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
    if (cmd.parameterNumber == 9) { // Slide start
        def realButtonNum = cmd.configurationValue[0]
        def direction = cmd.configurationValue[1]
        def buttonNum = (wallmoteButtons as int) * (2 - direction) + realButtonNum // 1-4 for press/hold/release, 5-8 for slide up, 9-12 for down
        buttonEvent(buttonNum, "pushed")
        logging("${device.displayName} button ${realButtonNum} slide ${ direction == 1 ? "Up" : "Down" } start (button ${ buttonNum })")
    } else if (cmd.parameterNumber == 10) { // Slide stop - Seems to be more reliable
        def realButtonNum = cmd.configurationValue[0]
        def direction = cmd.configurationValue[1]
        def buttonNum = (wallmoteButtons as int) * (2 - direction) + realButtonNum // 1-4 for press/hold/release, 5-8 for slide up, 9-12 for down
        logging("${device.displayName} button ${realButtonNum} slide ${ direction == 1 ? "Up" : "Down" } finish (button ${ buttonNum })")
    } else {
        update_current_properties(cmd)
        logging("${device.displayName} parameter '${cmd.parameterNumber}' with a byte size of '${cmd.size}' is set to '${cmd2Integer(cmd.configurationValue)}' (${cmd.configurationValue})")
    }
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    if (cmd.parameterNumber == 9) { // Slide start
        def realButtonNum = cmd.configurationValue[0]
        def direction = cmd.configurationValue[1]
        def buttonNum = (wallmoteButtons as int) * (2 - direction) + realButtonNum // 1-4 for press/hold/release, 5-8 for slide up, 9-12 for down
        buttonEvent(buttonNum, "pushed")
        logging("${device.displayName} button ${realButtonNum} slide ${ direction == 1 ? "Up" : "Down" } start (button ${ buttonNum })")
    } else if (cmd.parameterNumber == 10) { // Slide stop - Seems to be more reliable
        def realButtonNum = cmd.configurationValue[0]
        def direction = cmd.configurationValue[1]
        def buttonNum = (wallmoteButtons as int) * (2 - direction) + realButtonNum // 1-4 for press/hold/release, 5-8 for slide up, 9-12 for down
        logging("${device.displayName} button ${realButtonNum} slide ${ direction == 1 ? "Up" : "Down" } finish (button ${ buttonNum })")
    } else {
        update_current_properties(cmd)
        logging("${device.displayName} parameter '${cmd.parameterNumber}' with a byte size of '${cmd.size}' is set to '${cmd2Integer(cmd.configurationValue)}' (${cmd.configurationValue})")
    }
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
	def fw = "${cmd.applicationVersion}.${cmd.applicationSubVersion}"
	updateDataValue("fw", fw)
	if (state.MSR == "003B-6341-5044") {
		updateDataValue("ver", "${cmd.applicationVersion >> 4}.${cmd.applicationVersion & 0xF}")
	}
	def text = "$device.displayName: firmware version: $fw, Z-Wave version: ${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}"
	createEvent(descriptionText: text, isStateChange: false)
}

def zwaveEvent(hubitat.zwave.Command cmd) {
	logging("Unhandled zwaveEvent: ${cmd}")
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	log.debug "msr: $msr"
    updateDataValue("MSR", msr)
}

def installed() {
    logging("installed()")
    configure()
}

/**
* Triggered when Done button is pushed on Preference Pane
*/
def updated()
{
    logging("updated() is being called")
    state.wakeCount = 1
    cmds = []
    sendEvent(name: "checkInterval", value: 2 * 60 * 12 * 60 + 5 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
    sendEvent(name: "numberOfButtons", value: wallmoteQuad ? 12 : 6, displayed: true)
    sendEvent(name: "needUpdate", value: device.currentValue("needUpdate"), displayed:false, isStateChange: true)
    if (cmds != []) response(commands(cmds))
}

def configure() {
    cmds = []
	state.enableDebugging = settings.enableDebugging
    logging("Configuring Device For SmartThings Use")
    state.desiredProperties = [
        "1": enableTouchBeep ? 1 : 0,
        "2": enableTouchVibration ? 1 : 0,
        "3": enableSlide ? 1 : 0,
        "4": 3
    ]
    state.currentProperties = [:]
    sendEvent(name: "numberOfButtons", value: wallmoteQuad ? 12 : 6, displayed: true)
        cmds.add(zwave.versionV1.versionGet())
    if (!state.lastBatteryReport || (now() - state.lastBatteryReport) / 60000 >= 60 * 24)
    {
        logging("Over 24hr since last battery report. Requesting report")
        cmds.add(zwave.batteryV1.batteryGet())
    }
    logging(state.desiredProperties)
    if (!state.currentProperties.containsKey("1") || cmd2Integer(state.currentProperties."1") != (enableTouchBeep ? 1 : 0)) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 1, size: 1, scaledConfigurationValue: (enableTouchBeep ? 1 : 0)))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 1))
    if (!state.currentProperties.containsKey("2") || cmd2Integer(state.currentProperties."2") != (enableTouchVibration ? 1 : 0)) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, scaledConfigurationValue: (enableTouchVibration ? 1 : 0)))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 2))
    if (!state.currentProperties.containsKey("3") || cmd2Integer(state.currentProperties."3") != (enableSlide ? 1 : 0)) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, scaledConfigurationValue: (enableSlide ? 1 : 0)))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 3))
    if (!state.currentProperties.containsKey("4") || cmd2Integer(state.currentProperties."4") != 3) {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 4, size: 1, scaledConfigurationValue: 3))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 4))
    cmds.add(zwave.wakeUpV1.wakeUpNoMoreInformation())
    
    logging("Sending commands")
    logging(cmds)
    return cmds
}

def ping() {
    logging("ping()")
	logging("Battery Device - Not sending ping commands")
}

def update_current_properties(cmd)
{
    def currentProperties = state.currentProperties ?: [:]
    
    currentProperties."${cmd.parameterNumber}" = cmd.configurationValue

    if (settings."${cmd.parameterNumber}" != null)
    {
        if (convertParam(cmd.parameterNumber, settings."${cmd.parameterNumber}") == cmd2Integer(cmd.configurationValue))
        {
            sendEvent(name:"needUpdate", value:"NO", displayed:false, isStateChange: true)
        }
        else
        {
            sendEvent(name:"needUpdate", value:"YES", displayed:false, isStateChange: true)
        }
    }

    state.currentProperties = currentProperties
}

def convertParam(number, value) {
    long parValue
	switch (number){
    	case 5:
            switch (value) {
                case "1": 
                parValue = 4278190080
                break
                case "2": 
                parValue = 16711680
                break
                case "3": 
                parValue = 65280
                break
                default:
                parValue = value
                break
            }
        break
        default:
        	parValue = value.toLong()
        break
    }
    return parValue
}

private def logging(message) {
    if (logEnable == null || logEnable == "true") log.debug "$message"
}

/**
* Convert 1 and 2 bytes values to integer
*/
def cmd2Integer(array) { 
long value
    if (array != [255, 0, 0, 0]){
        switch(array.size()) {    
            case 1:
                value = array[0]
            break
            case 2:
                value = ((array[0] & 0xFF) << 8) | (array[1] & 0xFF)
            break
            case 3:
                value = ((array[0] & 0xFF) << 16) | ((array[1] & 0xFF) << 8) | (array[2] & 0xFF)
            break
            case 4:
                value = ((array[0] & 0xFF) << 24) | ((array[1] & 0xFF) << 16) | ((array[2] & 0xFF) << 8) | (array[3] & 0xFF)
            break
        }
    } else {
         value = 4278190080
    }
    return value
}

def integer2Cmd(value, size) {
	switch(size) {
	case 1:
		[value.toInteger()]
    break
	case 2:
    	def short value1   = value & 0xFF
        def short value2 = (value >> 8) & 0xFF
        [value2.toInteger(), value1.toInteger()]
    break
    case 3:
    	def short value1   = value & 0xFF
        def short value2 = (value >> 8) & 0xFF
        def short value3 = (value >> 16) & 0xFF
        [value3.toInteger(), value2.toInteger(), value1.toInteger()]
    break
	case 4:
    	def short value1 = value & 0xFF
        def short value2 = (value >> 8) & 0xFF
        def short value3 = (value >> 16) & 0xFF
        def short value4 = (value >> 24) & 0xFF
		[value4.toInteger(), value3.toInteger(), value2.toInteger(), value1.toInteger()]
	break
	}
}

def logsOff() {
    logEnable = false
}

private command(hubitat.zwave.Command cmd) {
    secureCmd(cmd)
}

String secureCmd(cmd) {
    if (getDataValue("zwaveSecurePairingComplete") == "true" && getDataValue("S2") == null) {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
		return cmd.format()
    }	
}

String secure(String cmd){
    return zwaveSecureEncap(cmd)
}

String secure(hubitat.zwave.Command cmd){
    return zwaveSecureEncap(cmd)
}


private commands(commands, delay=200) {
	def cmds = delayBetween(commands.collect{ command(it) }, delay)
    logging(cmds)
    return cmds
}

void sendToDevice(List<hubitat.zwave.Command> cmds) {
    logging(commands(cmds))
    sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(hubitat.zwave.Command cmd) {
    sendHubCommand(new hubitat.device.HubAction(secureCommand(cmd), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(secureCommand(cmd), hubitat.device.Protocol.ZWAVE))
}

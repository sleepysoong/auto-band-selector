package com.sleepysoong.autobandselector

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BandSelectorService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rootNode = rootInActiveWindow ?: return

        val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
        val deviceCarrier = prefs.getString("device_carrier", "SKT") ?: "SKT"
        val simCarrier = prefs.getString("sim_carrier", "SKT") ?: "SKT"
        val macroMode = prefs.getString("macro_mode", "") ?: ""
        val targetBand = prefs.getString("target_band_to_set", "") ?: ""

        if (macroMode.isEmpty() || targetBand.isEmpty()) return

        // 0. Dialer input auto-completion
        // Samsung dialer secret code handler is triggered by character-by-character IME input or physical clicks.
        // Bulk setting of "*#319712358#" at once via ACTION_SET_TEXT bypasses the dialer code detector.
        // Therefore, we set "*#319712358" (excluding the final '#'), and then programmatically
        // click the '#' button on the dialpad to simulate a real touch event, triggering the hidden menu!
        val digitsNode = findNodeContainingNumber(rootNode, "319712358")
        if (digitsNode != null) {
            val txt = digitsNode.text?.toString() ?: ""
            if (!txt.contains("*#")) {
                Log.d("BandSelectorBot", "Found dialer digits field: $txt. Writing prefix and digits without last #...")
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "*#319712358")
                val success = digitsNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d("BandSelectorBot", "Set text result: $success")
                
                if (success) {
                    try {
                        Thread.sleep(150) // Brief pause to let text settle
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    val hashButton = findHashButton(rootNode)
                    if (hashButton != null) {
                        Log.d("BandSelectorBot", "Found '#' button on dialpad, simulating click event...")
                        var clickTarget: AccessibilityNodeInfo? = hashButton
                        while (clickTarget != null && !clickTarget.isClickable) {
                            clickTarget = clickTarget.parent
                        }
                        if (clickTarget != null) {
                            clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } else {
                            hashButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    } else {
                        Log.e("BandSelectorBot", "Could not find '#' button on the dialpad screen")
                    }
                }
                return
            }
        }

        // 1. Password Auto-Input (Depends on Device firmware Carrier)
        val isPasswordScreen = findNodeByText(rootNode, "Password") != null || 
                               findNodeByText(rootNode, "비밀번호") != null
        if (isPasswordScreen) {
            val pwd = when (deviceCarrier) {
                "SKT" -> "996412"
                "KT" -> "774632"
                "LGU+" -> "0821"
                else -> "996412"
            }
            val editText = findEditableNode(rootNode)
            if (editText != null) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pwd)
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                
                val okButton = findNodeByText(rootNode, "OK") ?: findNodeByText(rootNode, "확인")
                okButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return
        }

        // 2. Navigation Steps
        val networkSettingsNode = findNodeByText(rootNode, "Network Settings")
        if (networkSettingsNode != null) {
            networkSettingsNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        val networkModeNode = findNodeByText(rootNode, "Network mode")
        if (networkModeNode != null) {
            networkModeNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        val bandSelectionNode = findNodeByText(rootNode, "Band Selection")
        if (bandSelectionNode != null) {
            bandSelectionNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // 3. Band Selection Screen Automation
        val selectionHeader = findNodeByText(rootNode, "SELECTION")
        if (selectionHeader != null) {
            Log.d("BandSelectorBot", "Target Band to set: $targetBand")
            
            val checkboxes = mutableListOf<AccessibilityNodeInfo>()
            findAllCheckboxes(rootNode, checkboxes)
            
            if (targetBand == "Automatic") {
                // For Automatic, just make sure Automatic is checked
                for (cb in checkboxes) {
                    val text = cb.text?.toString() ?: ""
                    if (text.contains("Automatic", ignoreCase = true)) {
                        if (!cb.isChecked) {
                            cb.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                }
            } else {
                // For specific bands (e.g. LTE B1)
                // First uncheck Automatic
                for (cb in checkboxes) {
                    val text = cb.text?.toString() ?: ""
                    if (text.contains("Automatic", ignoreCase = true)) {
                        if (cb.isChecked) {
                            cb.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                }
                
                // Check target band, and uncheck other bands we don't want (Depends on SIM Carrier bands)
                val carrierBands = getBandsForCarrier(simCarrier)
                for (cb in checkboxes) {
                    val text = cb.text?.toString() ?: ""
                    if (text.contains(targetBand, ignoreCase = true)) {
                        if (!cb.isChecked) {
                            cb.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    } else {
                        // Uncheck other carrier bands
                        for (otherBand in carrierBands) {
                            if (otherBand != targetBand && text.contains(otherBand, ignoreCase = true)) {
                                if (cb.isChecked) {
                                    cb.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                }
                            }
                        }
                    }
                }
            }
            
            // Apply selection
            selectionHeader.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            // Mark as applied to let MainActivity proceed
            prefs.edit().putBoolean("band_setting_applied", true).apply()
            
            // Return back to MainActivity to continue the speed test
            if (macroMode == "SCANNING") {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } else {
                // Done applying best or reverting. Return Home.
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun findNodeContainingNumber(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val txt = root.text?.toString()?.replace("-", "")?.replace(" ", "") ?: ""
        if (txt.contains(target)) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val res = findNodeContainingNumber(child, target)
                if (res != null) return res
            }
        }
        return null
    }

    private fun findHashButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        if (text == "#" || desc.contains("#") || desc.contains("우물정") || desc.contains("hash") || desc.contains("pound")) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val res = findHashButton(child)
                if (res != null) return res
            }
        }
        return null
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByText(text)
        return if (list.isNotEmpty()) list[0] else null
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.className == "android.widget.EditText") return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val res = findEditableNode(child)
                if (res != null) return res
            }
        }
        return null
    }

    private fun findAllCheckboxes(root: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (root.className == "android.widget.CheckBox") {
            list.add(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                findAllCheckboxes(child, list)
            }
        }
    }

    private fun getBandsForCarrier(carrier: String): List<String> {
        return when (carrier) {
            "SKT" -> listOf("LTE B1", "LTE B3", "LTE B5", "LTE B7")
            "KT" -> listOf("LTE B1", "LTE B3", "LTE B8")
            "LGU+" -> listOf("LTE B1", "LTE B5", "LTE B7")
            else -> listOf("LTE B1", "LTE B5", "LTE B7")
        }
    }

    override fun onInterrupt() {
        Log.e("BandSelectorBot", "Service interrupted")
    }
}

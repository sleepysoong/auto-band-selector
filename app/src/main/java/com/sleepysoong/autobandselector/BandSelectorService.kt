package com.sleepysoong.autobandselector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BandSelectorService : AccessibilityService() {

    private var macroState = 0
    private var isMacroRunning = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rootNode = rootInActiveWindow ?: return

        val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
        val carrier = prefs.getString("carrier", "SKT") ?: "SKT"
        val macroMode = prefs.getString("macro_mode", "") ?: ""

        // Check if dialer is launched and macro is requested recently
        // For simplicity, we just use the UI state to advance the macro

        val isPasswordScreen = findNodeByText(rootNode, "Password") != null || 
                               findNodeByText(rootNode, "비밀번호") != null
        
        if (isPasswordScreen) {
            Log.d("BandSelectorBot", "Detected password screen")
            val pwd = when (carrier) {
                "SKT" -> "996412"
                "KT" -> "774632"
                "LGU+" -> "0821"
                else -> "996412"
            }
            
            // In Android, we can simulate typing by injecting text into EditText if accessible
            // Or we simulate clicks on the dialpad/keyboard
            val editText = findEditableNode(rootNode)
            if (editText != null) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pwd)
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                
                // Click OK button
                val okButton = findNodeByText(rootNode, "OK") ?: findNodeByText(rootNode, "확인")
                okButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        // Navigate: Network Settings
        val networkSettingsNode = findNodeByText(rootNode, "Network Settings")
        if (networkSettingsNode != null) {
            networkSettingsNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // Navigate: Network mode
        val networkModeNode = findNodeByText(rootNode, "Network mode")
        if (networkModeNode != null) {
            networkModeNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // Navigate: Band Selection
        val bandSelectionNode = findNodeByText(rootNode, "Band Selection")
        if (bandSelectionNode != null) {
            bandSelectionNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // Band Selection Screen
        val selectionHeader = findNodeByText(rootNode, "SELECTION")
        if (selectionHeader != null) {
            val isForceMode = macroMode == "FORCE_BANDS"
            
            // This logic requires traversing list of checkboxes.
            // A simplified robust way is to click "Automatic" to check it (if reverting)
            // or uncheck Automatic and check "LTE B1" and "LTE B7" (if forcing).
            
            // To prevent infinite loops in the same screen, we'd need more careful state management
            // Here is a basic implementation of checking/unchecking:
            
            if (isForceMode) {
                val automaticNode = findNodeByText(rootNode, "Automatic")
                if (automaticNode?.isChecked == true) {
                    automaticNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                
                val b1Node = findNodeByText(rootNode, "LTE B1")
                if (b1Node != null && b1Node.isChecked == false) {
                    b1Node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                
                val b7Node = findNodeByText(rootNode, "LTE B7")
                if (b7Node != null && b7Node.isChecked == false) {
                    b7Node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } else { // REVERT
                val automaticNode = findNodeByText(rootNode, "Automatic")
                if (automaticNode != null && automaticNode.isChecked == false) {
                    automaticNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            
            // Click SELECTION
            selectionHeader.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            // Finish Macro, return to Home
            prefs.edit().putString("macro_mode", "").apply()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
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

    override fun onInterrupt() {
        Log.e("BandSelectorBot", "Service interrupted")
    }
}

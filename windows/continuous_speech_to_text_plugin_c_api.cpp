#include "include/continuous_speech_to_text/continuous_speech_to_text_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "continuous_speech_to_text_plugin.h"

void ContinuousSpeechToTextPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  continuous_speech_to_text::ContinuousSpeechToTextPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}

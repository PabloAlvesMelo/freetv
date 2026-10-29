#!/bin/bash
# Reativa os apps desativados no Mi TV Stick (rodar no Git Bash com o stick no USB)
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
for p in tv.alphonso.alphonso_eula android.autoinstalls.config.xiaomi.mibox3 android.autoinstalls.config.xioami.mibox3 com.xiaomi.android.tvsetup.partnercustomizer com.google.android.tv com.mitv.tvhome.atv com.mitv.tvhome.michannel com.google.android.videos com.google.android.feedback com.google.android.tv.bugreportsender com.android.printspooler com.xiaomi.mitv.updateservice com.aicareles.dlnaserver com.google.android.marvin.talkback com.mitv.dream; do
  "$ADB" shell pm enable "$p"
done

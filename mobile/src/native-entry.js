import { Capacitor, registerPlugin, SystemBars, SystemBarsStyle } from '@capacitor/core';
import { App } from '@capacitor/app';
import { Network } from '@capacitor/network';
import { Preferences } from '@capacitor/preferences';
import { Share } from '@capacitor/share';
import { Filesystem } from '@capacitor/filesystem';

window.OneStepNative = {
  isNative: Capacitor.isNativePlatform(), platform: Capacitor.getPlatform(), App, Network, Preferences, Share, Filesystem,
  FlightNative: registerPlugin('FlightNative'),
  FlightPush: registerPlugin('FlightPush'),
  FlightLiveActivity: registerPlugin('FlightLiveActivity')
};
if (Capacitor.getPlatform() === 'android') SystemBars.setStyle({ style: SystemBarsStyle.Light }).catch(() => {});

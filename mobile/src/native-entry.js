import { Capacitor, registerPlugin } from '@capacitor/core';
import { App } from '@capacitor/app';
import { Network } from '@capacitor/network';
import { Preferences } from '@capacitor/preferences';
import { Share } from '@capacitor/share';
import { Filesystem } from '@capacitor/filesystem';

window.OneStepNative = {
  isNative: Capacitor.isNativePlatform(), App, Network, Preferences, Share, Filesystem,
  FlightNative: registerPlugin('FlightNative'),
  FlightPush: registerPlugin('FlightPush'),
  FlightLiveActivity: registerPlugin('FlightLiveActivity')
};

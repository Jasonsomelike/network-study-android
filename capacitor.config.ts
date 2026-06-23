import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'cn.bestijason.networkstudy',
  appName: '知行网络学堂',
  webDir: 'www',
  server: {
    url: 'https://www.jasonsome.cn',
    cleartext: false,
    allowNavigation: [
      'www.jasonsome.cn',
      'jasonsome.cn',
      'dify.jasonsome.cn',
    ],
  },
  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 900,
      launchAutoHide: true,
      backgroundColor: '#12221e',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
    },
    StatusBar: {
      style: 'LIGHT',
      backgroundColor: '#f3f1eb',
      overlaysWebView: false,
    },
  },
}

export default config

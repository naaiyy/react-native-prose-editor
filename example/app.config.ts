const rootPackage = require('../package.json');

export default {
    expo: {
        name: 'Native Editor Example',
        slug: 'native-editor-example',
        version: rootPackage.version,
        plugins: [
            '@openeditor/react-native-prose-editor',
            [
                'expo-image-picker',
                {
                    photosPermission:
                        'Allow Native Editor Example to choose images from your library.',
                    cameraPermission: false,
                    microphonePermission: false,
                },
            ],
        ],
        orientation: 'portrait',
        ios: {
            supportsTablet: true,
            bundleIdentifier: 'com.openeditor.nativeeditorexample',
        },
        android: {
            package: 'com.openeditor.nativeeditorexample',
        },
    },
};

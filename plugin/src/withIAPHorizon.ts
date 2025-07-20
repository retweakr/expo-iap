import {
  ConfigPlugin,
  withAppBuildGradle,
  withGradleProperties,
} from '@expo/config-plugins';
import * as fs from 'fs';
import * as path from 'path';

// Global flag to prevent duplicate logs
let hasLoggedPluginExecution = false;

// Function to read Horizon app ID from package.json
const getHorizonAppId = (config: any): string => {
  try {
    // Try to get from expo config first
    if (config?.horizon?.appId) {
      return config.horizon.appId;
    }

    // Try to read from package.json in the project root
    const projectRoot = config?.projectRoot || process.cwd();
    const packageJsonPath = path.join(projectRoot, 'package.json');

    if (fs.existsSync(packageJsonPath)) {
      const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
      if (packageJson?.horizon?.appId) {
        console.log(
          `🌅 expo-iap: Found Horizon app ID in package.json: ${packageJson.horizon.appId}`,
        );
        return packageJson.horizon.appId;
      }
    }

    throw new Error('No Horizon app ID found in config or package.json');
  } catch (error) {
    const errorMessage = [
      '🌅 expo-iap: Error reading Horizon app ID from package.json:',
      error instanceof Error ? error.message : String(error),
      '🌅 expo-iap: Please ensure your package.json has the correct Horizon configuration:',
      '🌅 expo-iap: {',
      '🌅 expo-iap:   "horizon": {',
      '🌅 expo-iap:     "appId": "YOUR_QUEST_APP_ID"',
      '🌅 expo-iap:   }',
      '🌅 expo-iap: }',
      '🌅 expo-iap: You can find your Quest App ID in the Quest Developer Dashboard under Development > API',
    ].join('\n');

    console.error(errorMessage);
    throw new Error('Failed to get Horizon app ID');
  }
}

const addLineToGradle = (
  content: string,
  anchor: RegExp | string,
  lineToAdd: string,
  offset: number = 1,
): string => {
  const lines = content.split('\n');
  const index = lines.findIndex((line) => line.match(anchor));
  if (index === -1) {
    console.warn(
      `Anchor "${anchor}" not found in build.gradle. Appending to end.`,
    );
    lines.push(lineToAdd);
  } else {
    lines.splice(index + offset, 0, lineToAdd);
  }
  return lines.join('\n');
};

const modifyAppBuildGradle = (gradle: string, horizonAppId: string): string => {
  let modified = gradle;

  // No longer adding Horizon Maven repository

  // Add version variables before dependencies block
  const androidPlatformSdkVersion = '72';
  const horizonBillingCompatibilitySdkVersion = '1.1.1';

  const versionVariables = `
// Horizon SDK versions
def androidPlatformSdkVersion = "${androidPlatformSdkVersion}"
def horizonBillingCompatibilitySdkVersion = "${horizonBillingCompatibilitySdkVersion}"
`;

  // Add version variables before dependencies block
  if (!modified.includes('androidPlatformSdkVersion')) {
    modified = addLineToGradle(
      modified,
      /dependencies\s*{/,
      versionVariables,
      0,
    );
  }

  // Add BuildConfig fields for Horizon
  const buildConfigFields = [
    `        buildConfigField "boolean", "IS_HORIZON", "true"`,
    `        buildConfigField "String", "QUEST_APP_ID", '${horizonAppId}'`,
  ];

  // Try to find the defaultConfig block to add the BuildConfig fields
  const defaultConfigMatch = modified.match(/defaultConfig\s*\{/);
  if (defaultConfigMatch) {
    // Check and add IS_HORIZON field
    if (!modified.includes('buildConfigField "boolean", "IS_HORIZON"')) {
      modified = addLineToGradle(
        modified,
        /defaultConfig\s*\{/,
        buildConfigFields[0],
      );
    }

    // Check and add QUEST_APP_ID field
    if (!modified.includes('buildConfigField "String", "QUEST_APP_ID"')) {
      modified = addLineToGradle(
        modified,
        /defaultConfig\s*\{/,
        buildConfigFields[1],
      );
    }
  } else {
    console.warn(
      'Could not find defaultConfig block to add Horizon BuildConfig fields',
    );
  }

  // Add Kotlin compiler options to skip metadata version check
  if (!modified.includes('-Xskip-metadata-version-check')) {
    const kotlinOptionsBlock = `
    kotlinOptions {
        freeCompilerArgs += ["-Xskip-metadata-version-check"]
    }`;

    // Try to find the android block to add kotlinOptions
    const androidBlockMatch = modified.match(/android\s*\{/);
    if (androidBlockMatch) {
      // Find the closing brace of the android block
      let openBraces = 1;
      let closeIndex = androidBlockMatch.index! + androidBlockMatch[0].length;

      while (openBraces > 0 && closeIndex < modified.length) {
        if (modified[closeIndex] === '{') openBraces++;
        if (modified[closeIndex] === '}') openBraces--;
        closeIndex++;
      }

      // Insert kotlinOptions before the closing brace of the android block
      if (closeIndex > 0 && closeIndex <= modified.length) {
        modified =
          modified.substring(0, closeIndex - 1) +
          kotlinOptionsBlock +
          '\n' +
          modified.substring(closeIndex - 1);
      }
    }
  }

  // Add Horizon Billing Compat SDK dependencies
  const horizonPlatformDep = `    implementation "com.meta.horizon.platform.ovr:android-platform-sdk:${androidPlatformSdkVersion}"`;
  const horizonBillingDep = `    implementation "com.meta.horizon.billingclient.api:horizon-billing-compatibility:${horizonBillingCompatibilitySdkVersion}"`;

  let hasAddedDependency = false;

  if (
    !modified.includes('com.meta.horizon.platform.ovr:android-platform-sdk')
  ) {
    modified = addLineToGradle(
      modified,
      /dependencies\s*{/,
      horizonPlatformDep,
    );
    hasAddedDependency = true;
  }

  if (
    !modified.includes(
      'com.meta.horizon.billingclient.api:horizon-billing-compatibility',
    )
  ) {
    modified = addLineToGradle(modified, /dependencies\s*{/, horizonBillingDep);
    hasAddedDependency = true;
  }

  // Log only once and only if we actually added dependencies
  if (hasAddedDependency && !hasLoggedPluginExecution) {
    console.log(
      '🌅 expo-iap: Added Horizon Billing Compat SDK dependencies to build.gradle',
    );
  }

  return modified;
};

const withIAPHorizon: ConfigPlugin = (config) => {
  console.log('🌅 expo-iap: Using Horizon Billing Compat SDK');
  console.log('🌅 expo-iap: Config keys:', Object.keys(config || {}));
  console.log('🌅 expo-iap: Config horizon:', (config as any)?.horizon);

  // Get the Horizon app ID
  const horizonAppId = getHorizonAppId(config);
  console.log(`🌅 expo-iap: Using Horizon app ID: ${horizonAppId}`);

  // Add Horizon Billing Compat SDK dependencies to app build.gradle
  config = withAppBuildGradle(config, (config) => {
    config.modResults.contents = modifyAppBuildGradle(
      config.modResults.contents,
      horizonAppId,
    );
    return config;
  });

  // Add horizonEnabled=true to gradle.properties
  config = withGradleProperties(config, (config) => {
    // Add the horizonEnabled property
    config.modResults.push({
      type: 'property',
      key: 'horizonEnabled',
      value: 'true',
    });

    if (!hasLoggedPluginExecution) {
      console.log(
        '🌅 expo-iap: Added horizonEnabled=true to gradle.properties',
      );
    }

    return config;
  });

  hasLoggedPluginExecution = true;
  return config;
};

export default withIAPHorizon;

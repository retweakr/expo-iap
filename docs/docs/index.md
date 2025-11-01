---
sidebar_position: 1
---

import AdFitTopFixed from "@site/src/uis/AdFitTopFixed"; import SponsorSection from '@site/src/components/SponsorSection';

# Expo IAP

<AdFitTopFixed />

**Expo IAP** is a powerful in-app purchase solution for Expo and React Native applications that **conforms to the [Open IAP specification](https://openiap.dev)**. It provides a unified API for handling in-app purchases across iOS and Android platforms with comprehensive error handling and modern TypeScript support.

If you're shipping an app with expo-iap, we’d love to hear about it—please share your product and feedback in [Who’s using Expo IAP?](https://github.com/hyochan/expo-iap/discussions/143). Community stories help us keep improving the ecosystem.

## Promotion

<div align="center">
  <a href="https://hyodotdev.github.io/kstyled" target="_blank" rel="noopener noreferrer">
    <img src="https://hyodotdev.github.io/kstyled/img/logo.png" alt="kstyled Logo" width="120" />
  </a>

**Compile-time CSS-in-JS for React Native**

✨ Experience the next generation of styling with **[kstyled](https://hyodotdev.github.io/kstyled)** - a blazing-fast, fully type-safe CSS-in-JS solution with zero runtime overhead.

🚀 **[Explore kstyled →](https://hyodotdev.github.io/kstyled)**

</div>

## Sponsors & Community Support

We're building the OpenIAP ecosystem—defining the spec at [openiap.dev](https://www.openiap.dev), maintaining [OpenIAP](https://github.com/hyodotdev/openiap) for the shared type system, and shipping native SDKs such as [openiap-apple](https://github.com/hyodotdev/openiap-apple) and [openiap-google](https://github.com/hyodotdev/openiap-google). These modules power [expo-iap](https://github.com/hyochan/expo-iap), [flutter_inapp_purchase](https://github.com/hyochan/flutter_inapp_purchase), [kmp-iap](https://github.com/hyochan/kmp-iap), and [react-native-iap](https://github.com/hyochan/react-native-iap). After simplifying fragmented APIs, the next milestone is a streamlined purchase flow: `initConnection → fetchProducts → requestPurchase → (server receipt validation) → finishTransaction`.

Your sponsorship keeps this work moving—ensuring more developers across platforms, OS, and frameworks can implement IAPs without headaches while we expand to additional plugins and payment systems. Sponsors receive shout-outs in each release and, depending on tier, can request tailored support. If you’re interested—or have rollout feedback to share—you can view sponsorship options at [openiap.dev/sponsors](https://www.openiap.dev/sponsors).

<SponsorSection variant="compact" showLabel />

## 📚 Guides

- [**Installation**](./getting-started/installation): Complete guide to implementing in-app purchases
- [**Purchase Lifecycle**](./guides/lifecycle): Understanding connection management and best practices
- [**Purchase Implementation**](./guides/purchases): Detailed purchase flow and event handling
- [**FAQ**](./guides/faq): Frequently asked questions and solutions
- [**Support**](./guides/support): Getting help and community resources

## 🚀 Quick Start

### Installation

Install the package using your favorite package manager:

```bash
npm install expo-iap
```

### 1. Basic Setup

First, import and initialize the IAP hook:

```tsx
import {useIAP} from 'expo-iap';

function MyStore() {
  const {
    connected,
    products,
    fetchProducts,
    requestPurchase,
    finishTransaction,
  } = useIAP({
    onPurchaseSuccess: async (purchase) => {
      console.log('Purchase successful:', purchase);

      // IMPORTANT: Verify receipt on your backend before finishing transaction
      const isValid = await verifyReceiptOnServer(purchase);

      if (isValid) {
        await finishTransaction({purchase, isConsumable: true});
      }
    },
    onPurchaseError: (error) => {
      console.error('Purchase failed:', error);
    },
  });

  const productIds = ['your.product.id', 'your.premium.subscription'];
}
```

### 2. Fetch Products

Load your products when the store connects:

```tsx
useEffect(() => {
  if (connected) {
    // Fetch your products
    fetchProducts({skus: productIds, type: 'in-app'});
  }
}, [connected]);
```

### 3. Display Products

Show available products to users:

```tsx
return (
  <View>
    <Text>Store Status: {connected ? 'Connected' : 'Connecting...'}</Text>

    {products.map((product) => (
      <View key={product.id} style={styles.productItem}>
        <Text style={styles.productTitle}>{product.title}</Text>
        <Text style={styles.productPrice}>{product.displayPrice}</Text>
        <Button title="Buy Now" onPress={() => handlePurchase(product.id)} />
      </View>
    ))}
  </View>
);
```

### 4. Handle Purchases

Process purchase requests with our new platform-specific API (v2.7.0+):

```tsx
const handlePurchase = async (productId: string) => {
  try {
    await requestPurchase({
      request: {
        ios: {
          sku: productId,
        },
        android: {
          skus: [productId],
        },
      },
    });
  } catch (error) {
    console.error('Purchase failed:', error);
  }
};
```

**No more Platform.OS checks!** The new API automatically handles platform differences. iOS can only purchase one product at a time, while Android supports purchasing multiple products in a single transaction.

### 5. Complete Transactions

Finish purchases in the success callback:

```tsx
const {connected, products, fetchProducts, requestPurchase, finishTransaction} =
  useIAP({
    onPurchaseSuccess: async (purchase) => {
      try {
        console.log('Purchase completed:', purchase.id);

        // IMPORTANT: Verify receipt on your backend before finishing transaction
        const isValid = await verifyReceiptOnServer(purchase);

        if (!isValid) {
          console.error('Receipt validation failed');
          return;
        }

        // Grant the purchase to user here
        await grantPurchaseToUser(purchase);

        // Finish the transaction
        await finishTransaction({
          purchase,
          isConsumable: true, // Set based on your product type
        });
      } catch (error) {
        console.error('Failed to complete purchase:', error);
      }
    },
    onPurchaseError: (error) => {
      console.error('Purchase failed:', error);
    },
  });
```

### Complete Basic Example

Here's a complete working example:

```tsx
import React, {useEffect} from 'react';
import {View, Text, Button, StyleSheet} from 'react-native';
import {useIAP} from 'expo-iap';

export default function SimpleStore() {
  const {
    connected,
    products,
    fetchProducts,
    requestPurchase,
    finishTransaction,
  } = useIAP({
    onPurchaseSuccess: async (purchase) => {
      try {
        console.log('Purchase completed:', purchase.id);

        // IMPORTANT: Verify receipt on your backend before finishing transaction
        const isValid = await verifyReceiptOnServer(purchase);

        if (!isValid) {
          console.error('Receipt validation failed');
          return;
        }

        // Grant purchase to user
        await grantPurchaseToUser(purchase);

        // Finish the transaction
        await finishTransaction({
          purchase,
          isConsumable: true,
        });
      } catch (error) {
        console.error('Failed to complete purchase:', error);
      }
    },
    onPurchaseError: (error) => {
      console.error('Purchase failed:', error);
    },
  });

  const productIds = ['com.example.coins.pack1', 'com.example.premium'];

  useEffect(() => {
    if (connected) {
      fetchProducts({skus: productIds, type: 'in-app'});
    }
  }, [connected]);

  const handlePurchase = async (productId: string) => {
    try {
      await requestPurchase({
        request: {
          ios: {
            sku: productId,
          },
          android: {
            skus: [productId],
          },
        },
      });
    } catch (error) {
      console.error('Purchase failed:', error);
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.status}>
        Store: {connected ? 'Connected ✅' : 'Connecting...'}
      </Text>

      {products.map((product) => (
        <View key={product.id} style={styles.product}>
          <Text style={styles.title}>{product.title}</Text>
          <Text style={styles.price}>{product.displayPrice}</Text>
          <Button title="Buy Now" onPress={() => handlePurchase(product.id)} />
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {padding: 20},
  status: {fontSize: 16, marginBottom: 20},
  product: {
    padding: 15,
    marginVertical: 5,
    backgroundColor: '#f0f0f0',
    borderRadius: 8,
  },
  title: {fontSize: 16, fontWeight: 'bold'},
  price: {fontSize: 14, color: '#666', marginVertical: 5},
});
```

## 🏗️ Architecture

Expo IAP is built with a modern architecture that emphasizes:

- **Type Safety**: Comprehensive TypeScript definitions for all APIs
- **Error Resilience**: Centralized error handling with meaningful error codes
- **Platform Abstraction**: Unified API that handles platform differences internally
- **Performance**: Optimized for minimal bundle size and runtime performance

## 📱 Platform Support

| Platform          | Support | Notes                                        |
| ----------------- | ------- | -------------------------------------------- |
| iOS               | ✅      | StoreKit 1 & 2 (StoreKit 2 requires iOS 15+) |
| Android           | ✅      | Google Play Billing v5+                      |
| Expo Go           | ⚠️      | Limited (requires custom development client) |
| Expo Dev Client   | ✅      | Full support                                 |
| Bare React Native | ✅      | Full support                                 |

## 🎯 What's Next?

### 📦 Setup & Configuration

- [**Installation Guide**](./installation): Install and configure Expo IAP
- [**iOS Setup**](./getting-started/setup-ios): App Store Connect and Xcode configuration
- [**Android Setup**](./getting-started/setup-android): Google Play Console setup

### 🔧 Implementation

- [**API Reference**](./api/use-iap): Complete useIAP hook documentation
- [**Purchase Flow Example**](./examples/purchase-flow): Simple product purchase flow
- [**Available Purchases Example**](./examples/available-purchases): Restore and list prior purchases

### 📚 Guides

- [**Installation**](./getting-started/installation): Complete guide to implementing in-app purchases
- [**Purchase Lifecycle**](./guides/lifecycle): Understanding connection management and best practices
- [**Purchase Implementation**](./guides/purchases): Detailed purchase flow and event handling
- [**FAQ**](./guides/faq): Frequently asked questions and solutions
- [**Support**](./guides/support): Getting help and community resources

### 🛠️ Advanced Topics

- [**Receipt Validation**](./guides/purchases): Secure purchase validation
- [**Error Handling**](./api/error-codes): Comprehensive error management
- [**Subscriptions Flow Example**](./examples/subscription-flow): Handle recurring subscriptions
- [**Troubleshooting**](./guides/troubleshooting): Common issues and solutions

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](https://github.com/hyochan/expo-iap/blob/main/CONTRIBUTING.md) for details.

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](https://github.com/hyochan/expo-iap/blob/main/LICENSE) file for details.

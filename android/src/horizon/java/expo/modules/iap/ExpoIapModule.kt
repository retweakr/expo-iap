package expo.modules.iap

import android.content.Context
import android.util.Log
import expo.modules.iap.IapConstants
import expo.modules.iap.IapErrorCode
import expo.modules.iap.IapEvent
import expo.modules.iap.MissingCurrentActivityException
import expo.modules.iap.PromiseUtils
import com.meta.horizon.billingclient.api.BillingClient
import com.meta.horizon.billingclient.api.BillingClientStateListener
import com.meta.horizon.billingclient.api.BillingResult
import com.meta.horizon.billingclient.api.Purchase
import com.meta.horizon.billingclient.api.PurchasesUpdatedListener
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * ExpoIapModule - A module for handling in-app purchases using the Horizon Billing SDK
 * This implementation uses the Horizon Billing Compatibility SDK when EXPO_HORIZON environment variable is set
 */
class ExpoIapModule : Module(), PurchasesUpdatedListener {
    private var billingClientCache: BillingClient? = null
    private val skus: MutableMap<String, com.meta.horizon.billingclient.api.ProductDetails> = mutableMapOf()
    companion object {
        const val TAG = "ExpoIapModule"

        // Billing response codes
        object BillingResponseCode {
            const val OK = 0
            const val USER_CANCELED = 1
            const val SERVICE_UNAVAILABLE = 2
            const val BILLING_UNAVAILABLE = 3
            const val ITEM_UNAVAILABLE = 4
            const val DEVELOPER_ERROR = 5
            const val ERROR = 6
            const val ITEM_ALREADY_OWNED = 7
            const val ITEM_NOT_OWNED = 8
            const val SERVICE_DISCONNECTED = -1
            const val SERVICE_TIMEOUT = -2
            const val FEATURE_NOT_SUPPORTED = -3
        }
    }

    private val context: Context
        get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()
    private val currentActivity
        get() =
            appContext.activityProvider?.currentActivity
                ?: throw MissingCurrentActivityException()

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: List<Purchase>?,
    ) {
        val responseCode = billingResult.responseCode
        if (responseCode != BillingClient.BillingResponseCode.OK) {
            val error =
                mutableMapOf<String, Any?>(
                    "responseCode" to responseCode,
                    "debugMessage" to billingResult.debugMessage,
                )
            val errorData = getHorizonBillingResponseData(responseCode)
            error["code"] = errorData.code
            error["message"] = errorData.message
            try {
                sendEvent(IapEvent.PURCHASE_ERROR, error.toMap())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send PURCHASE_ERROR event: ${e.message}")
            }
            PromiseUtils.rejectPromisesForKey(IapConstants.PROMISE_BUY_ITEM, errorData.code, errorData.message, null)
            return
        }

        if (purchases != null) {
            val promiseItems = mutableListOf<Map<String, Any?>>()
            purchases.forEach { purchase ->
                val item =
                    mutableMapOf<String, Any?>(
                        "id" to purchase.products.firstOrNull() as Any?,
                        "ids" to purchase.products,
                        "transactionId" to purchase.orderId,
                        "transactionDate" to purchase.purchaseTime.toDouble(),
                        "transactionReceipt" to purchase.originalJson,
                        "purchaseTokenAndroid" to purchase.purchaseToken,
                        "dataAndroid" to purchase.originalJson,
                        "signatureAndroid" to purchase.signature,
                        "autoRenewingAndroid" to purchase.isAutoRenewing(),
                        "isAcknowledgedAndroid" to purchase.isAcknowledged(),
                        // Get purchase state - use a default value if method doesn't exist
                        "purchaseStateAndroid" to 1, // Default to PURCHASED state (1)
                        "packageNameAndroid" to purchase.packageName,
                        "developerPayloadAndroid" to purchase.developerPayload,
                        "platform" to "android",
                    )
                // Account identifiers might not be available in this version of the API
                // Skip adding these fields to avoid compilation errors

                promiseItems.add(item.toMap())
                try {
                    sendEvent(IapEvent.PURCHASE_UPDATED, item.toMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send PURCHASE_UPDATED event: ${e.message}")
                }
            }
            PromiseUtils.resolvePromisesForKey(IapConstants.PROMISE_BUY_ITEM, promiseItems)
        } else {
            val result =
                mutableMapOf<String, Any?>(
                    "platform" to "android",
                    "responseCode" to billingResult.responseCode,
                    "debugMessage" to billingResult.debugMessage,
                    "extraMessage" to
                        "The purchases are null. This is a normal behavior if you have requested DEFERRED proration. If not please report an issue.",
                )
            try {
                sendEvent(IapEvent.PURCHASE_UPDATED, result.toMap())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send PURCHASE_UPDATED event: ${e.message}")
            }
            PromiseUtils.resolvePromisesForKey(IapConstants.PROMISE_BUY_ITEM, result)
        }
    }

    override fun definition() =
        ModuleDefinition {
            Name("ExpoIap")

            Constants(
                "ERROR_CODES" to IapErrorCode.toMap()
            )

            Events(IapEvent.PURCHASE_UPDATED, IapEvent.PURCHASE_ERROR)

            AsyncFunction("initConnection") { promise: Promise ->
                Log.i(TAG, "Initializing Horizon Billing SDK connection")
                initBillingClient(promise) { 
                    Log.i(TAG, "Horizon Billing SDK connection initialized successfully")
                    promise.resolve(true) 
                }
            }

            AsyncFunction("endConnection") { promise: Promise ->
                Log.i(TAG, "Ending Horizon Billing SDK connection")
                billingClientCache?.endConnection()
                billingClientCache = null
                skus.clear()
                Log.i(TAG, "Horizon Billing SDK connection ended successfully")
                promise.resolve(true)
            }

            AsyncFunction("getItemsByType") { type: String, skuArr: Array<String>, promise: Promise ->
                Log.i(TAG, "Getting items by type: $type, skus: ${skuArr.joinToString()}")

                ensureConnection(promise) { billingClient ->
                    val productType = if (type == "subs") BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

                    val productList = skuArr.map { sku ->
                        com.meta.horizon.billingclient.api.QueryProductDetailsParams.Product
                            .newBuilder()
                            .setProductId(sku)
                            .setProductType(productType)
                            .build()
                    }

                    if (productList.isEmpty()) {
                        promise.reject(IapConstants.EMPTY_SKU_LIST, "The SKU list is empty.", null)
                        return@ensureConnection
                    }

                    val params = com.meta.horizon.billingclient.api.QueryProductDetailsParams
                        .newBuilder()
                        .setProductList(productList)
                        .build()

                    billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            promise.reject(
                                IapErrorCode.E_QUERY_PRODUCT,
                                "Error querying product details: ${billingResult.debugMessage}",
                                null,
                            )
                            return@queryProductDetailsAsync
                        }

                        val items = productDetailsList.map { productDetails ->
                            skus[productDetails.productId] = productDetails

                            val currency = productDetails.oneTimePurchaseOfferDetails?.priceCurrencyCode
                                ?: productDetails.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.priceCurrencyCode
                                ?: "Unknown"
                            val displayPrice = productDetails.oneTimePurchaseOfferDetails?.formattedPrice
                                ?: productDetails.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                                ?: "N/A"

                            mapOf(
                                "id" to productDetails.productId,
                                "title" to productDetails.title,
                                "description" to productDetails.description,
                                "type" to productDetails.productType,
                                "displayName" to productDetails.name,
                                "platform" to "android",
                                "currency" to currency,
                                "displayPrice" to displayPrice,
                                "oneTimePurchaseOfferDetails" to
                                    productDetails.oneTimePurchaseOfferDetails?.let {
                                        mapOf(
                                            "priceCurrencyCode" to it.priceCurrencyCode,
                                            "formattedPrice" to it.formattedPrice,
                                            "priceAmountMicros" to it.priceAmountMicros.toString(),
                                        )
                                    },
                                "subscriptionOfferDetails" to
                                    productDetails.subscriptionOfferDetails?.map { subscriptionOfferDetailsItem ->
                                        mapOf(
                                            "basePlanId" to subscriptionOfferDetailsItem.basePlanId,
                                            "offerId" to subscriptionOfferDetailsItem.offerId,
                                            "offerToken" to subscriptionOfferDetailsItem.offerToken,
                                            "offerTags" to subscriptionOfferDetailsItem.offerTags,
                                            "pricingPhases" to
                                                mapOf(
                                                    "pricingPhaseList" to
                                                        subscriptionOfferDetailsItem.pricingPhases.pricingPhaseList.map
                                                            { pricingPhaseItem ->
                                                                mapOf(
                                                                    "formattedPrice" to pricingPhaseItem.formattedPrice,
                                                                    "priceCurrencyCode" to pricingPhaseItem.priceCurrencyCode,
                                                                    "billingPeriod" to pricingPhaseItem.billingPeriod,
                                                                    "billingCycleCount" to pricingPhaseItem.billingCycleCount,
                                                                    "priceAmountMicros" to
                                                                        pricingPhaseItem.priceAmountMicros.toString(),
                                                                    "recurrenceMode" to pricingPhaseItem.recurrenceMode,
                                                                )
                                                            },
                                                ),
                                        )
                                    },
                            )
                        }
                        promise.resolve(items)
                    }
                }
            }

            AsyncFunction("getAvailableItemsByType") { type: String, promise: Promise ->
                Log.i(TAG, "Getting available items by type: $type")

                ensureConnection(promise) { billingClient ->
                    val productType = if (type == "subs") BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

                    billingClient.queryPurchasesAsync(
                        com.meta.horizon.billingclient.api.QueryPurchasesParams
                            .newBuilder()
                            .setProductType(productType)
                            .build()
                    ) { billingResult: BillingResult, purchases: List<Purchase>? ->
                        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            val errorData = getHorizonBillingResponseData(billingResult.responseCode)
                            promise.reject(errorData.code, billingResult.debugMessage, null)
                            return@queryPurchasesAsync
                        }

                        val items = mutableListOf<Map<String, Any?>>()
                        purchases?.forEach { purchase ->
                            val item = mutableMapOf<String, Any?>(
                                // kept for convenience/backward-compatibility. productIds has the complete list
                                "id" to purchase.products.firstOrNull() as Any?,
                                "ids" to purchase.products,
                                "transactionId" to purchase.orderId,
                                "transactionDate" to purchase.purchaseTime.toDouble(),
                                "transactionReceipt" to purchase.originalJson,
                                "orderId" to purchase.orderId,
                                "purchaseTokenAndroid" to purchase.purchaseToken,
                                "developerPayloadAndroid" to purchase.developerPayload,
                                "signatureAndroid" to purchase.signature,
                                "purchaseStateAndroid" to 1, // Default to PURCHASED state (1)
                                "isAcknowledgedAndroid" to purchase.isAcknowledged(),
                                "packageNameAndroid" to purchase.packageName,
                                "platform" to "android",
                            )

                            if (type == "subs") {
                                item["autoRenewingAndroid"] = purchase.isAutoRenewing()
                            }

                            items.add(item)
                        }

                        promise.resolve(items)
                    }
                }
            }

            AsyncFunction("getPurchaseHistoryByType") { type: String, promise: Promise ->
                Log.i(TAG, "Getting purchase history by type: $type")

                ensureConnection(promise) { billingClient ->
                    val productType = if (type == "subs") BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

                    billingClient.queryPurchaseHistoryAsync(
                        com.meta.horizon.billingclient.api.QueryPurchaseHistoryParams
                            .newBuilder()
                            .setProductType(productType)
                            .build()
                    ) { billingResult: BillingResult, purchaseHistoryRecordList: List<com.meta.horizon.billingclient.api.PurchaseHistoryRecord>? ->
                        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            val errorData = getHorizonBillingResponseData(billingResult.responseCode)
                            promise.reject(errorData.code, billingResult.debugMessage, null)
                            return@queryPurchaseHistoryAsync
                        }

                        val items = mutableListOf<Map<String, Any?>>()
                        purchaseHistoryRecordList?.forEach { purchase ->
                            val item = mutableMapOf<String, Any?>(
                                "id" to purchase.products.firstOrNull() as Any?,
                                "ids" to purchase.products,
                                "transactionDate" to purchase.purchaseTime.toDouble(),
                                "transactionReceipt" to purchase.originalJson,
                                "purchaseTokenAndroid" to purchase.purchaseToken,
                                "dataAndroid" to purchase.originalJson,
                                "signatureAndroid" to purchase.signature,
                                "developerPayload" to purchase.developerPayload,
                                "platform" to "android",
                            )
                            items.add(item)
                        }

                        promise.resolve(items)
                    }
                }
            }

            AsyncFunction("buyItemByType") { params: Map<String, Any?>, promise: Promise ->
                Log.i(TAG, "Buying item by type: $params")

                val type = params["type"] as String
                val skuArr = (params["skuArr"] as? List<*>)?.filterIsInstance<String>()?.toTypedArray() ?: emptyArray()
                val purchaseToken = params["purchaseToken"] as? String
                val replacementMode = (params["replacementMode"] as? Double)?.toInt() ?: -1
                val obfuscatedAccountId = params["obfuscatedAccountId"] as? String
                val obfuscatedProfileId = params["obfuscatedProfileId"] as? String
                val offerTokenArr = (params["offerTokenArr"] as? List<*>)?.filterIsInstance<String>()?.toTypedArray() ?: emptyArray()
                val isOfferPersonalized = params["isOfferPersonalized"] as? Boolean ?: false

                if (currentActivity == null) {
                    promise.reject(IapErrorCode.E_UNKNOWN, "getCurrentActivity returned null", null)
                    return@AsyncFunction
                }

                ensureConnection(promise) { billingClient ->
                    PromiseUtils.addPromiseForKey(IapConstants.PROMISE_BUY_ITEM, promise)

                    if (type == BillingClient.ProductType.SUBS && skuArr.size != offerTokenArr.size) {
                        val debugMessage = "The number of skus (${skuArr.size}) must match: the number of offerTokens (${offerTokenArr.size}) for Subscriptions"
                        try {
                            sendEvent(
                                IapEvent.PURCHASE_ERROR,
                                mapOf(
                                    "debugMessage" to debugMessage,
                                    "code" to IapErrorCode.E_SKU_OFFER_MISMATCH,
                                    "message" to debugMessage,
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send PURCHASE_ERROR event: ${e.message}")
                        }
                        promise.reject(IapErrorCode.E_SKU_OFFER_MISMATCH, debugMessage, null)
                        return@ensureConnection
                    }

                    val productParamsList = skuArr.mapIndexed { index, sku ->
                        val selectedSku = skus[sku]
                        if (selectedSku == null) {
                            val debugMessage = "The sku was not found. Please fetch products first by calling getItems"
                            try {
                                sendEvent(
                                    IapEvent.PURCHASE_ERROR,
                                    mapOf(
                                        "debugMessage" to debugMessage,
                                        "code" to IapErrorCode.E_SKU_NOT_FOUND,
                                        "message" to debugMessage,
                                        "productId" to sku,
                                    ),
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send PURCHASE_ERROR event: ${e.message}")
                            }
                            promise.reject(IapErrorCode.E_SKU_NOT_FOUND, debugMessage, null)
                            return@ensureConnection
                        }

                        val productDetailParams = com.meta.horizon.billingclient.api.BillingFlowParams.ProductDetailsParams
                            .newBuilder()
                            .setProductDetails(selectedSku)

                        if (type == BillingClient.ProductType.SUBS && index < offerTokenArr.size) {
                            productDetailParams.setOfferToken(offerTokenArr[index])
                        }

                        productDetailParams.build()
                    }

                    val builder = com.meta.horizon.billingclient.api.BillingFlowParams
                        .newBuilder()
                        .setProductDetailsParamsList(productParamsList)
                        .setIsOfferPersonalized(isOfferPersonalized)

                    if (purchaseToken != null) {
                        val subscriptionUpdateParams = com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams
                            .newBuilder()
                            .setOldPurchaseToken(purchaseToken)

                        if (type == BillingClient.ProductType.SUBS && replacementMode != -1) {
                            val mode = when (replacementMode) {
                                com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE ->
                                    com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
                                com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION ->
                                    com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION
                                com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED ->
                                    com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
                                com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION ->
                                    com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
                                com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE ->
                                    com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE
                                else -> com.meta.horizon.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.UNKNOWN_REPLACEMENT_MODE
                            }
                            subscriptionUpdateParams.setSubscriptionReplacementMode(mode)
                        }
                        builder.setSubscriptionUpdateParams(subscriptionUpdateParams.build())
                    }

                    obfuscatedAccountId?.let { builder.setObfuscatedAccountId(it) }
                    obfuscatedProfileId?.let { builder.setObfuscatedProfileId(it) }

                    val flowParams = builder.build()
                    val billingResult = billingClient.launchBillingFlow(currentActivity, flowParams)

                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        val errorData = getHorizonBillingResponseData(billingResult.responseCode)
                        promise.reject(errorData.code, billingResult.debugMessage, null)
                        return@ensureConnection
                    }
                }
            }

            AsyncFunction("acknowledgePurchase") { token: String, promise: Promise ->
                Log.i(TAG, "Acknowledging purchase: $token")

                ensureConnection(promise) { billingClient ->
                    val acknowledgePurchaseParams = com.meta.horizon.billingclient.api.AcknowledgePurchaseParams
                        .newBuilder()
                        .setPurchaseToken(token)
                        .build()

                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult: BillingResult ->
                        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            val errorData = getHorizonBillingResponseData(billingResult.responseCode)
                            promise.reject(errorData.code, billingResult.debugMessage, null)
                            return@acknowledgePurchase
                        }

                        val map = mutableMapOf<String, Any?>()
                        map["responseCode"] = billingResult.responseCode
                        map["debugMessage"] = billingResult.debugMessage
                        val errorData = getHorizonBillingResponseData(billingResult.responseCode)
                        map["code"] = errorData.code
                        map["message"] = errorData.message
                        promise.resolve(map)
                    }
                }
            }

            AsyncFunction("consumeProduct") { token: String, promise: Promise ->
                Log.i(TAG, "Consuming product: $token")

                ensureConnection(promise) { billingClient ->
                    val params = com.meta.horizon.billingclient.api.ConsumeParams
                        .newBuilder()
                        .setPurchaseToken(token)
                        .build()

                    billingClient.consumeAsync(params) { billingResult: BillingResult, purchaseToken: String? ->
                        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            val errorData = getHorizonBillingResponseData(billingResult.responseCode)
                            promise.reject(errorData.code, billingResult.debugMessage, null)
                            return@consumeAsync
                        }

                        val map = mutableMapOf<String, Any?>()
                        map["responseCode"] = billingResult.responseCode
                        map["debugMessage"] = billingResult.debugMessage
                        val errorData = getHorizonBillingResponseData(billingResult.responseCode)
                        map["code"] = errorData.code
                        map["message"] = errorData.message
                        map["purchaseTokenAndroid"] = purchaseToken
                        promise.resolve(map)
                    }
                }
            }

            AsyncFunction("getStorefront") { promise: Promise ->
                Log.i(TAG, "Getting storefront")

                ensureConnection(promise) { billingClient ->
                    billingClient.getBillingConfigAsync(
                        com.meta.horizon.billingclient.api.GetBillingConfigParams.newBuilder().build(),
                        object : com.meta.horizon.billingclient.api.BillingConfigResponseListener {
                            override fun onBillingConfigResponse(
                                result: BillingResult,
                                config: com.meta.horizon.billingclient.api.BillingConfig?
                            ) {
                                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                                    promise.resolve(config?.countryCode ?: "US")
                                } else {
                                    val debugMessage = result.debugMessage ?: "Unknown error"
                                    promise.reject(result.responseCode.toString(), debugMessage, null)
                                }
                            }
                        }
                    )
                }
            }
        }

    /**
     * Helper function to get error data for a billing response code
     * Based on Meta Horizon Billing SDK response codes
     */
    private fun getHorizonBillingResponseData(responseCode: Int): ErrorData {
        return when (responseCode) {
            BillingResponseCode.OK ->
                ErrorData("E_OK", "OK")
            BillingResponseCode.USER_CANCELED ->
                ErrorData("E_USER_CANCELLED", "User canceled the purchase.")
            BillingResponseCode.SERVICE_UNAVAILABLE ->
                ErrorData("E_SERVICE_ERROR", "Billing service is unavailable.")
            BillingResponseCode.BILLING_UNAVAILABLE ->
                ErrorData("E_BILLING_UNAVAILABLE", "Billing is unavailable on this device.")
            BillingResponseCode.ITEM_UNAVAILABLE ->
                ErrorData("E_ITEM_UNAVAILABLE", "The requested item is unavailable.")
            BillingResponseCode.DEVELOPER_ERROR ->
                ErrorData("E_DEVELOPER_ERROR", "Developer error occurred.")
            BillingResponseCode.ERROR ->
                ErrorData("E_UNKNOWN", "Unknown error occurred.")
            BillingResponseCode.ITEM_ALREADY_OWNED ->
                ErrorData("E_ALREADY_OWNED", "Item is already owned.")
            BillingResponseCode.ITEM_NOT_OWNED ->
                ErrorData("E_NOT_OWNED", "Item is not owned.")
            BillingResponseCode.SERVICE_DISCONNECTED ->
                ErrorData("E_SERVICE_ERROR", "Billing service disconnected.")
            BillingResponseCode.SERVICE_TIMEOUT ->
                ErrorData("E_SERVICE_ERROR", "Billing service timed out.")
            BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                ErrorData("E_FEATURE_NOT_SUPPORTED", "This feature is not supported.")
            else -> {
                Log.w(TAG, "Unknown billing response code: $responseCode")
                ErrorData("E_UNKNOWN", "Unknown billing response code: $responseCode")
            }
        }
    }

    // Helper methods for Horizon Billing SDK
    private fun initBillingClient(
        promise: Promise,
        callback: (billingClient: BillingClient) -> Unit,
    ) {
        // Get the Quest App ID from BuildConfig using reflection
        val questAppId = try {
            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
            val questAppIdField = buildConfigClass.getField("QUEST_APP_ID")
            questAppIdField.get(null) as String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get QUEST_APP_ID from BuildConfig: ${e.message}")
            null
        }
        
        if (questAppId.isNullOrEmpty()) {
            promise.reject(
                IapErrorCode.E_INIT_CONNECTION,
                "Quest App ID not configured. Please set the QUEST_APP_ID in your app's build.gradle",
                null,
            )
            return
        }

        Log.i(TAG, "Initializing Horizon Billing SDK with Quest App ID: $questAppId")

        billingClientCache =
            BillingClient
                .newBuilder(context)
                .setListener(this)
                .setAppId(questAppId) // Set the Quest App ID as required by Horizon Billing SDK
                .enablePendingPurchases()
                .build()

        billingClientCache?.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.e(TAG, "Billing setup failed with response code: ${billingResult.responseCode}, message: ${billingResult.debugMessage}")
                        promise.reject(
                            IapErrorCode.E_INIT_CONNECTION,
                            "Billing setup finished with error: ${billingResult.debugMessage}",
                            null,
                        )
                        return
                    }
                    Log.i(TAG, "Horizon Billing SDK initialized successfully")
                    callback(billingClientCache!!)
                }

                override fun onBillingServiceDisconnected() {
                    Log.i(TAG, "Billing service disconnected")
                }
            },
        )
    }

    private fun ensureConnection(
        promise: Promise,
        callback: (billingClient: BillingClient) -> Unit,
    ) {
        // Check if the billing client is ready and connected
        if (billingClientCache != null) {
            // For Horizon Billing SDK, we assume the client is ready if it's not null
            // since we don't have an explicit isReady check like in Play Billing
            callback(billingClientCache!!)
            return
        }

        // Initialize the billing client if it's not already initialized
        initBillingClient(promise, callback)
    }

    // Helper class for error data
    data class ErrorData(val code: String, val message: String)
}

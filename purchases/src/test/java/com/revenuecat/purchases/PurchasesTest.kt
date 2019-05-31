//  Purchases
//
//  Copyright © 2019 RevenueCat, Inc. All rights reserved.
//

package com.revenuecat.purchases

import android.app.Activity
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails
import com.revenuecat.purchases.interfaces.Callback
import com.revenuecat.purchases.interfaces.GetSkusResponseListener
import com.revenuecat.purchases.interfaces.ReceivePurchaserInfoListener
import com.revenuecat.purchases.interfaces.UpdatedPurchaserInfoListener
import com.revenuecat.purchases.util.AdvertisingIdClient
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.AssertionsForClassTypes
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.ArrayList
import java.util.Collections.emptyList
import java.util.ConcurrentModificationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class PurchasesTest {

    private val mockBillingWrapper: BillingWrapper = mockk()
    private val mockBackend: Backend = mockk()
    private val mockCache: DeviceCache = mockk()
    private val listener: UpdatedPurchaserInfoListener = mockk()
    private val mockContext = mockk<Context>(relaxed = true)

    private var capturedPurchasesUpdatedListener = slot<BillingWrapper.PurchasesUpdatedListener>()

    private val appUserId = "fakeUserID"
    private val adID = "123"
    private lateinit var purchases: Purchases
    private var receivedSkus: List<SkuDetails>? = null
    private var receivedEntitlementMap: Map<String, Entitlement>? = null

    @After
    fun tearDown() {
        Purchases.backingFieldSharedInstance = null
        Purchases.postponedAttributionData = mutableListOf()
    }

    private fun setup(): PurchaserInfo {
        val mockInfo = mockk<PurchaserInfo>()

        mockCache(mockInfo)
        mockBackend(mockInfo)
        mockBillingWrapper()
        every {
            listener.onReceived(any())
        } just Runs

        purchases = Purchases(
            mockContext,
            appUserId,
            mockBackend,
            mockBillingWrapper,
            mockCache
        )
        Purchases.sharedInstance = purchases
        return mockInfo
    }

    private fun setupAnonymous() {
        val mockInfo = mockk<PurchaserInfo>()

        mockCache(mockInfo)
        mockBackend(mockInfo)
        mockBillingWrapper()
        every {
            listener.onReceived(any())
        } just Runs

        purchases = Purchases(
            mockContext,
            null,
            mockBackend,
            mockBillingWrapper,
            mockCache
        )
        Purchases.sharedInstance = purchases
    }

    @Test
    fun canBeCreated() {
        setup()
        assertThat(purchases).isNotNull
    }

    @Test
    fun getsSubscriptionSkus() {
        setup()

        val skus = ArrayList<String>()
        skus.add("onemonth_freetrial")

        val skuDetails = ArrayList<SkuDetails>()

        mockSkuDetailFetch(skuDetails, skus, BillingClient.SkuType.SUBS)

        purchases.getSubscriptionSkus(skus,
            object : GetSkusResponseListener {
                override fun onReceived(skus: MutableList<SkuDetails>) {
                    receivedSkus = skus
                }

                override fun onError(error: PurchasesError) {
                    fail("shouldn't be error")
                }
            })

        assertThat(receivedSkus).isEqualTo(skuDetails)
    }

    @Test
    fun getsNonSubscriptionSkus() {
        setup()

        val skus = ArrayList<String>()
        skus.add("normal_purchase")

        val skuDetails = ArrayList<SkuDetails>()

        mockSkuDetailFetch(skuDetails, skus, BillingClient.SkuType.INAPP)

        purchases.getNonSubscriptionSkus(skus,
            object : GetSkusResponseListener {
                override fun onReceived(skus: MutableList<SkuDetails>) {
                    receivedSkus = skus
                }

                override fun onError(error: PurchasesError) {
                    fail("shouldn't be error")
                }
            })

        assertThat(receivedSkus).isEqualTo(skuDetails)
    }

    @Test
    fun canMakePurchaseDeprecated() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"
        val oldSkus = ArrayList<String>()

        every {
            mockBillingWrapper.querySkuDetailsAsync(any(), any(), captureLambda(), any())
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(listOf(mockk(relaxed = true)))
        }

        purchases.makePurchaseWith(
            activity,
            sku,
            BillingClient.SkuType.SUBS
        ) { _, _ -> }

        verify (exactly = 1) {
            mockBillingWrapper.makePurchaseAsync(
                eq(activity),
                eq(appUserId),
                eq(sku),
                eq(oldSkus),
                eq(BillingClient.SkuType.SUBS)
            )
        }
    }

    @Test
    fun canMakePurchase() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"
        val skuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns sku
        }

        purchases.makePurchaseWith(
            activity,
            skuDetails
        ) { _, _ -> }

        verify {
            mockBillingWrapper.makePurchaseAsync(
                eq(activity),
                eq(appUserId),
                skuDetails,
                null
            )
        }
    }

    @Test
    fun postsSuccessfulPurchasesToBackend() {
        setup()

        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                any()
            )
        }
        verify {
            mockBillingWrapper.consumePurchase(eq(purchaseToken))
        }
    }

    @Test
    fun callsPostForEachUpdatedPurchase() {
        setup()

        val purchasesList = ArrayList<Purchase>()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        for (i in 0..1) {
            val p: Purchase = mockk()
            every {
                p.sku
            } returns sku
            every {
                p.purchaseToken
            } returns purchaseToken + Integer.toString(i)
            purchasesList.add(p)
        }

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(purchasesList)

        verify(exactly = 2) {
            mockBackend.postReceiptData(
                any(),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                any()
            )
        }
    }

    @Test
    fun doesntPostIfNotOK() {
        setup()

        capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(emptyList(), 0, "fail")

        verify(exactly = 0) {
            mockBackend.postReceiptData(
                any(),
                any(),
                any(),
                eq(false),
                any(),
                any()
            )
        }
    }

    @Test
    fun passesUpErrorsDeprecated() {
        setup()
        var errorCalled = false
        purchases.makePurchaseWith(
            mockk(),
            "sku",
            "SKUS",
            onError = { error, _ ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
            }) { _, _ -> }

        val purchase = mockk<Purchase>(relaxed = true)
        every { purchase.sku } returns "sku"
        capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(listOf(purchase), 2, "")
        assertThat(errorCalled).isTrue()
    }

    @Test
    fun passesUpErrors() {
        setup()
        var errorCalled = false
        purchases.makePurchaseWith(
            mockk(),
            mockk<SkuDetails>().also {
                every { it.sku } returns "sku"
            },
            onError = { error, _ ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
            }, onSuccess = { _, _ -> })

        val purchase = mockk<Purchase>(relaxed = true)
        every { purchase.sku } returns "sku"
        capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(listOf(purchase), 2, "")
        assertThat(errorCalled).isTrue()
    }

    @Test
    fun getsSubscriberInfoOnCreated() {
        setup()

        verify {
            mockBackend.getPurchaserInfo(eq(appUserId), any(), any())
        }
    }

    @Test
    fun canBeSetupWithoutAppUserID() {
        val mockInfo = mockk<PurchaserInfo>()

        mockCache(mockInfo)
        mockBackend(mockInfo)
        mockBillingWrapper()

        val purchases = Purchases(
            mockContext,
            null,
            mockBackend,
            mockBillingWrapper,
            mockCache
        )

        assertThat(purchases).isNotNull

        val appUserID = purchases.appUserID
        assertThat(appUserID).isNotNull()
        assertThat(appUserID.length).isEqualTo(36)
    }

    @Test
    fun storesGeneratedAppUserID() {
        val mockInfo = mockk<PurchaserInfo>()

        mockCache(mockInfo)
        mockBackend(mockInfo)
        mockBillingWrapper()

        Purchases(
            mockContext,
            null,
            mockBackend,
            mockBillingWrapper,
            mockCache
        )

        verify {
            mockCache.cacheAppUserID(any())
        }
    }

    @Test
    fun pullsUserIDFromCache() {
        setup()

        val appUserID = "random_id"
        every {
            mockCache.getCachedAppUserID()
        } returns appUserID
        val p = Purchases(
            mockContext,
            null,
            mockBackend,
            mockBillingWrapper,
            mockCache
        )

        assertThat(appUserID).isEqualTo(p.appUserID)
    }

    @Test
    fun isRestoreWhenUsingNullAppUserID() {
        setup()

        val purchases = Purchases(
            mockContext,
            null,
            mockBackend,
            mockBillingWrapper,
            mockCache
        )

        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        val purchasesList = ArrayList<Purchase>()

        purchasesList.add(p)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(purchasesList)

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(purchases.appUserID),
                eq(sku),
                eq(true),
                any(),
                any()
            )
        }
    }

    @Test
    fun doesntRestoreNormally() {
        setup()

        val purchases = Purchases(
            mockContext,
            "a_fixed_id",
            mockBackend,
            mockBillingWrapper,
            mockCache
        )

        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        val purchasesList = ArrayList<Purchase>()

        purchasesList.add(p)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(purchasesList)

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(purchases.appUserID),
                eq(sku),
                eq(false),
                any(),
                any()
            )
        }
    }

    @Test
    fun canOverrideAnonMode() {
        setup()

        val purchases = Purchases(
            mockContext,
            "a_fixed_id",
            mockBackend,
            mockBillingWrapper,
            mockCache
        )
        purchases.allowSharingPlayStoreAccount = true

        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        val purchasesList = ArrayList<Purchase>()

        purchasesList.add(p)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(purchasesList)

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(purchases.appUserID),
                eq(sku),
                eq(true),
                any(),
                any()
            )
        }
    }

    @Test
    fun restoringPurchasesGetsHistory() {
        setup()
        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(listOf(mockk(relaxed = true)))
        }

        purchases.restorePurchasesWith { }

        verify {
            mockBillingWrapper.queryAllPurchases(
                any(),
                any()
            )
        }
    }

    @Test
    fun historicalPurchasesPassedToBackend() {
        setup()

        val p: Purchase = mockk(relaxed = true)
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(listOf(p))
        }

        var restoreCalled = false
        purchases.restorePurchasesWith(onSuccess = {
            restoreCalled = true
        }, onError = {
            fail("Should not be an error")
        })
        assertThat(restoreCalled).isTrue()

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(purchases.appUserID),
                eq(sku),
                eq(true),
                any(),
                any()
            )
        }
    }

    @Test
    fun failedToRestorePurchases() {
        setup()
        val purchasesError = PurchasesError(PurchasesErrorCode.StoreProblemError, "Broken")
        every {
            mockBillingWrapper.queryAllPurchases(any(), captureLambda())
        } answers {
            lambda<(PurchasesError) -> Unit>().captured.invoke(purchasesError)
        }

        var onErrorCalled = false
        purchases.restorePurchasesWith(onSuccess = {
            fail("should be an error")
        }, onError = { error ->
            onErrorCalled = true
            assertThat(error).isEqualTo(purchasesError)
        })

        assertThat(onErrorCalled).isTrue()
    }

    @Test
    fun restoringCallsRestoreCallback() {
        setup()

        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        val purchasesList = ArrayList<Purchase>()

        purchasesList.add(p)

        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(purchasesList)
        }

        val mockInfo = mockk<PurchaserInfo>()
        every {
            mockBackend.postReceiptData(
                any(),
                any(),
                any(),
                eq(true),
                captureLambda(),
                any()
            )
        } answers {
            lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
        }

        var callbackCalled = false
        purchases.restorePurchasesWith(onSuccess = { info ->
            assertThat(mockInfo).isEqualTo(info)
            callbackCalled = true
        }, onError = {
            fail("should be success")
        })

        verify(exactly = 1) {
            mockBillingWrapper.queryAllPurchases(any(), any())
        }

        assertThat(callbackCalled).isTrue()
    }

    @Test
    fun receivedPurchaserInfoShouldBeCached() {
        setup()

        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        val purchasesList = ArrayList<Purchase>()

        purchasesList.add(p)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(purchasesList)

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                any()
            )
        }
        verify(exactly = 2) {
            mockCache.cachePurchaserInfo(
                any(),
                any()
            )
        }
    }

    @Test
    fun getEntitlementsHitsBackend() {
        mockProducts(listOf())
        mockSkuDetails(listOf(), listOf(), "subs")

        setup()

        verify {
            mockBackend.getEntitlements(any(), any(), any())
        }
    }

    @Test
    fun getEntitlementsPopulatesMissingSkuDetails() {
        setup()

        val skus = listOf("monthly")

        mockProducts(skus)
        val details = mockSkuDetails(skus, skus, BillingClient.SkuType.SUBS)

        purchases.getEntitlementsWith(onSuccess = { entitlementMap ->
            this@PurchasesTest.receivedEntitlementMap = entitlementMap
        }, onError = {
            fail("should be a success")
        })

        assertThat(receivedEntitlementMap).isNotNull

        verify {
            mockBillingWrapper.querySkuDetailsAsync(
                eq(BillingClient.SkuType.SUBS),
                eq(skus),
                any(),
                any()
            )
        }

        val e = receivedEntitlementMap!!["pro"]
        assertThat(e!!.offerings.size).isEqualTo(1)
        val o = e.offerings["monthly_offering"]
        assertThat(o!!.skuDetails).isEqualTo(details[0])
    }

    @Test
    fun getEntitlementsDoesntCheckInappsUnlessThereAreMissingSubs() {

        val skus = ArrayList<String>()
        val subsSkus = ArrayList<String>()
        skus.add("monthly")
        subsSkus.add("monthly")

        val inappSkus = ArrayList<String>()
        skus.add("monthly_inapp")
        inappSkus.add("monthly_inapp")

        mockProducts(skus)
        mockSkuDetails(skus, subsSkus, BillingClient.SkuType.SUBS)
        mockSkuDetails(inappSkus, inappSkus, BillingClient.SkuType.INAPP)

        setup()

        every {
            mockBillingWrapper.querySkuDetailsAsync(
                eq(BillingClient.SkuType.SUBS),
                eq<List<String>>(skus),
                any(),
                any()
            )
        }
        every {
            mockBillingWrapper.querySkuDetailsAsync(
                eq(BillingClient.SkuType.INAPP),
                eq<List<String>>(inappSkus),
                any(),
                any()
            )
        }
    }

    @Test
    fun getEntitlementsIsCached() {
        setup()
        val skus = ArrayList<String>()
        skus.add("monthly")
        mockProducts(skus)
        mockSkuDetails(skus, skus, BillingClient.SkuType.SUBS)

        purchases.getEntitlementsWith(onSuccess = { entitlementMap ->
            receivedEntitlementMap = entitlementMap
        }, onError = {
            fail("should be a success")
        })

        assertThat(receivedEntitlementMap).isNotNull
        assertThat(purchases.state.cachedEntitlements).isEqualTo(receivedEntitlementMap)
    }

    @Test
    fun getEntitlementsErrorIsNotCalledIfSkuDetailsMissing() {
        setup()

        val skus = listOf("monthly")
        mockProducts(skus)
        mockSkuDetails(skus, ArrayList(), BillingClient.SkuType.SUBS)
        mockSkuDetails(skus, ArrayList(), BillingClient.SkuType.INAPP)

        val errorMessage = emptyArray<PurchasesError>()

        purchases.getEntitlementsWith(onSuccess = { entitlementMap ->
            receivedEntitlementMap = entitlementMap
        }, onError = { error ->
            errorMessage[0] = error
        })

        assertThat(errorMessage.size).isZero()
        assertThat(this.receivedEntitlementMap).isNotNull
    }

    @Test
    fun getEntitlementsErrorIsCalledIfNoBackendResponse() {
        setup()

        every {
            mockBackend.getEntitlements(
                any(),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError) -> Unit>().captured.invoke(
                PurchasesError(PurchasesErrorCode.StoreProblemError)
            )
        }

        var purchasesError: PurchasesError? = null

        purchases.getEntitlementsWith(onSuccess = {
            fail("should be an error")
        }, onError = { error ->
            purchasesError = error
        })

        assertThat(purchasesError).isNotNull
    }

    @Test
    fun addAttributionPassesDataToBackend() {
        setup()

        val jsonObject = JSONObject()
        jsonObject.put("key", "value")
        val network = Purchases.AttributionNetwork.APPSFLYER

        val jsonSlot = slot<JSONObject>()
        every {
            mockBackend.postAttributionData(appUserId, network, capture(jsonSlot), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkid"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(jsonObject, network, networkUserID)

        verify { mockBackend.postAttributionData(appUserId, network, any(), any()) }
        assertThat(jsonSlot.captured["key"]).isEqualTo("value")
    }

    @Test
    fun addAttributionConvertsStringStringMapToJsonObject() {
        setup()

        val network = Purchases.AttributionNetwork.APPSFLYER

        every {
            mockBackend.postAttributionData(appUserId, network, any(), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        verify {
            mockBackend.postAttributionData(
                eq(appUserId),
                eq(network),
                any(),
                any()
            )
        }
    }

    @Test
    fun consumesNonSubscriptionPurchasesOn40x() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError, Boolean) -> Unit>().captured.invoke(
                PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                true
            )
        }

        setup()

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(mockk<Purchase>().also {
            every {
                it.sku
            } returns sku
            every {
                it.purchaseToken
            } returns purchaseToken
        }))

        verify {
            mockBillingWrapper.consumePurchase(eq(purchaseToken))
        }
    }

    @Test
    fun triesToConsumeNonSubscriptionPurchasesOn50x() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError, Boolean) -> Unit>().captured.invoke(
                PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                true
            )
        }

        setup()

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(mockk<Purchase>().also {
            every {
                it.sku
            } returns sku
            every {
                it.purchaseToken
            } returns purchaseToken
        }))

        verify {
            mockBillingWrapper.consumePurchase(eq(purchaseToken))
        }
    }

    @Test
    fun closeCloses() {
        setup()
        mockCloseActions()

        purchases.close()

        verify { mockBackend.close() }
        verifyOrder {
            mockBillingWrapper.purchasesUpdatedListener = capturedPurchasesUpdatedListener.captured
            mockBillingWrapper.purchasesUpdatedListener = null
        }
    }

    @Test
    fun whenNoTokensRestoringPurchasesStillCallListener() {
        setup()

        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(emptyList())
        }

        val mockCompletion = mockk<ReceivePurchaserInfoListener>(relaxed = true)
        purchases.restorePurchases(mockCompletion)

        verify {
            mockCompletion.onReceived(any())
        }
    }

    @Test
    fun `given a successful aliasing, success handler is called`() {
        setup()
        every {
            mockBackend.createAlias(
                eq(appUserId),
                eq("new_id"),
                captureLambda(),
                any()
            )
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }
        val mockInfo = mockk<PurchaserInfo>()
        every {
            mockBackend.getPurchaserInfo(any(), captureLambda(), any())
        } answers {
            lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
        }

        val mockCompletion = mockk<ReceivePurchaserInfoListener>(relaxed = true)
        purchases.createAlias(
            "new_id",
            mockCompletion
        )

        verify {
            mockCompletion.onReceived(mockInfo)
        }
    }

    @Test
    fun `given an unsuccessful aliasing, onError handler is called`() {
        setup()
        val purchasesError = PurchasesError(PurchasesErrorCode.InvalidCredentialsError)
        every {
            mockBackend.createAlias(
                eq(appUserId),
                eq("new_id"),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError) -> Unit>().captured.invoke(purchasesError)
        }
        val mockReceivePurchaserInfoListener = mockk<ReceivePurchaserInfoListener>(relaxed = true)
        purchases.createAlias(
            "new_id",
            mockReceivePurchaserInfoListener
        )
        verify {
            mockReceivePurchaserInfoListener.onError(eq(purchasesError))
        }
    }

    @Test
    fun `given a successful aliasing, appUserID is identified`() {
        setup()
        every {
            mockBackend.createAlias(
                eq(appUserId),
                eq("new_id"),
                captureLambda(),
                any()
            )
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        every {
            mockBackend.getPurchaserInfo(eq("new_id"), captureLambda(), any())
        } answers {
            lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockk())
        }

        purchases.createAlias("new_id")

        verify(exactly = 2) {
            mockCache.cachePurchaserInfo(any(), any())
        }
        assertThat(purchases.allowSharingPlayStoreAccount).isEqualTo(false)
        assertThat(purchases.appUserID).isEqualTo("new_id")
    }

    @Test
    fun `when identifying, appUserID is identified`() {
        setup()

        every {
            mockBackend.getPurchaserInfo("new_id", captureLambda(), any())
        } answers {
            lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockk(relaxed = true))
        }

        purchases.identify("new_id")

        verify(exactly = 2) {
            mockCache.cachePurchaserInfo(any(), any())
        }
        assertThat(purchases.allowSharingPlayStoreAccount).isEqualTo(false)
        assertThat(purchases.appUserID).isEqualTo("new_id")
    }

    @Test
    fun `when resetting, random app user id is generated and saved`() {
        setup()
        every {
            mockCache.clearLatestAttributionData(appUserId)
        } just Runs
        purchases.reset()
        val randomID = slot<String>()
        verify {
            mockCache.cacheAppUserID(capture(randomID))
        }
        verify {
            mockCache.clearLatestAttributionData(appUserId)
        }
        assertThat(purchases.appUserID).isEqualTo(randomID.captured)
        assertThat(randomID.captured).isNotNull()
    }

    @Test
    fun `when setting up, and passing a appUserID, user is identified`() {
        setup()
        verify(exactly = 1) {
            mockCache.cachePurchaserInfo(any(), any())
        }
        assertThat(purchases.allowSharingPlayStoreAccount).isEqualTo(false)
        assertThat(purchases.appUserID).isEqualTo(appUserId)
    }

    @Test
    fun `when setting listener, caches are retrieved`() {
        setup()

        purchases.updatedPurchaserInfoListener = listener

        verify {
            listener.onReceived(any())
        }
    }

    @Test
    fun `when setting listener, purchases are restored`() {
        setup()
        verify(exactly = 0) {
            mockBillingWrapper.queryPurchaseHistoryAsync(any(), any(), any())
        }
    }

    @Test
    fun `when setting shared instance and there's already an instance, instance is closed`() {
        setup()
        mockCloseActions()
        Purchases.sharedInstance = purchases
        Purchases.sharedInstance = purchases
        verify {
            purchases.close()
        }
    }

    @Test
    fun `when setting listener, listener is called`() {
        setup()
        purchases.updatedPurchaserInfoListener = listener

        verify(exactly = 1) {
            listener.onReceived(any())
        }
    }

    @Test
    fun `when setting listener for anonymous user, listener is called`() {
        setupAnonymous()
        purchases.updatedPurchaserInfoListener = listener

        verify(exactly = 1) {
            listener.onReceived(any())
        }
    }

    @Test
    fun `given a random purchase update, listener is called if purchaser info has changed`() {
        setup()
        val info = mockk<PurchaserInfo>()
        every {
            mockBackend.postReceiptData(any(), any(), any(), any(), captureLambda(), any())
        } answers {
            lambda<(PurchaserInfo) -> Unit>().captured.invoke(info)
        }
        purchases.updatedPurchaserInfoListener = listener
        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))

        verify(exactly = 2) {
            listener.onReceived(any())
        }
    }

    @Test
    fun `given a random purchase update, listener is not called if purchaser info has not changed`() {
        setup()
        purchases.updatedPurchaserInfoListener = listener
        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))

        verify(exactly = 1) {
            listener.onReceived(any())
        }
    }

    @Test
    fun `DEPRECATED when making another purchase for a product for a pending product, error is issued`() {
        setup()
        purchases.updatedPurchaserInfoListener = listener
        val sku = "onemonth_freetrial"

        purchases.makePurchaseWith(
            mockk(),
            sku,
            BillingClient.SkuType.SUBS,
            onError = { _, _ -> fail("Should be success") }) { _, _ ->
                // First one works
            }

        var errorCalled: PurchasesError? = null
        purchases.makePurchaseWith(
            mockk(),
            sku,
            BillingClient.SkuType.SUBS,
            onError = { error, _  ->
                errorCalled = error
            }) { _, _ ->
                fail("Should be error")
            }

        assertThat(errorCalled!!.code).isEqualTo(PurchasesErrorCode.OperationAlreadyInProgressError)
    }

    @Test
    fun `when making another purchase for a product for a pending product, error is issued`() {
        setup()
        purchases.updatedPurchaserInfoListener = listener

        val skuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns "sku"
        }
        purchases.makePurchaseWith(
            mockk(),
            skuDetails,
            onError = { _, _ -> fail("Should be success") }) { _, _ ->
            // First one works
        }

        var errorCalled: PurchasesError? = null
        purchases.makePurchaseWith(
            mockk(),
            skuDetails,
            onError = { error, _  ->
                errorCalled = error
            }) { _, _ ->
            fail("Should be error")
        }

        assertThat(errorCalled!!.code).isEqualTo(PurchasesErrorCode.OperationAlreadyInProgressError)
    }

    @Test
    fun `DEPRECATED when making purchase, completion block is called once`() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"

        val p: Purchase = mockk()
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        var callCount = 0
        purchases.makePurchaseWith(
            activity,
            sku,
            BillingClient.SkuType.SUBS,
            onSuccess = { _, _ ->
                callCount++
            }, onError = { _, _ -> fail("should be successful") })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))

        assertThat(callCount).isEqualTo(1)
    }

    @Test
    fun `when making purchase, completion block is called once`() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"

        val p: Purchase = mockk()
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        val skuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns sku
        }

        var callCount = 0
        purchases.makePurchaseWith(
            activity,
            skuDetails,
            onSuccess = { _, _ ->
                callCount++
            }, onError = { _, _ -> fail("should be successful") })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))

        assertThat(callCount).isEqualTo(1)
    }

    @Test
    fun `DEPRECATED when making purchase, completion block not called for different products`() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"
        val sku1 = "onemonth_freetrial_1"

        val p: Purchase = mockk()
        val p1: Purchase = mockk()
        val purchaseToken = "crazy_purchase_token"
        val purchaseToken1 = "crazy_purchase_token_1"
        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken
        every {
            p1.sku
        } returns sku1
        every {
            p1.purchaseToken
        } returns purchaseToken1

        var callCount = 0
        purchases.makePurchaseWith(
            activity,
            sku,
            BillingClient.SkuType.SUBS,
            onSuccess = { _, _ ->
                callCount++
            }, onError = { _, _ -> fail("should be successful") })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p1))

        assertThat(callCount).isEqualTo(0)
    }

    @Test
    fun `when making purchase, completion block not called for different products`() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"
        val sku1 = "onemonth_freetrial_1"

        val p: Purchase = mockk()
        val p1: Purchase = mockk()
        val purchaseToken = "crazy_purchase_token"
        val purchaseToken1 = "crazy_purchase_token_1"
        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken
        every {
            p1.sku
        } returns sku1
        every {
            p1.purchaseToken
        } returns purchaseToken1

        var callCount = 0
        purchases.makePurchaseWith(
            activity,
            mockk<SkuDetails>().also {
                every { it.sku } returns sku
            },
            onSuccess = { _, _ ->
                callCount++
            }, onError = { _, _ -> fail("should be successful") })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p1))

        assertThat(callCount).isEqualTo(0)
    }

    @Test
    fun `sends cached purchaser info to getter`() {
        val info = setup()

        every {
            mockBackend.getPurchaserInfo(any(), captureLambda(), any())
        } answers {
            // Timeout
        }

        var receivedInfo: PurchaserInfo? = null
        purchases.getPurchaserInfoWith(onSuccess = {
            receivedInfo = it
        }, onError = {
            fail("supposed to be successful")
        })

        assertThat(receivedInfo).isEqualTo(info)
    }

    @Test
    fun `given no cached purchaser info, backend is called again`() {
        setup()
        every {
            mockCache.getCachedPurchaserInfo(any())
        } returns null
        every {
            mockBackend.getPurchaserInfo(any(), captureLambda(), any())
        } answers {
            // Timeout
        }

        var receivedInfo: PurchaserInfo? = null
        purchases.getPurchaserInfoWith(onSuccess = {
            receivedInfo = it
        }, onError = {
            fail("supposed to be successful")
        })

        assertThat(receivedInfo).isEqualTo(null)
        verify(exactly = 2) { mockBackend.getPurchaserInfo(any(), any(), any()) }
    }


    @Test
    fun `don't create an alias if the new app user id is the same`() {
        setup()

        val lock = CountDownLatch(1)
        purchases.createAliasWith(appUserId) {
            lock.countDown()
        }

        lock.await(200, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isZero()
        verify (exactly = 0) {
            mockBackend.createAlias(appUserId, appUserId, any(), any())
        }
    }

    @Test
    fun `don't identify if the new app user id is the same`() {
        val info = setup()

        var receivedInfo: PurchaserInfo? = null
        purchases.identifyWith(appUserId) {
           receivedInfo = it
        }

        verify (exactly = 1) {
            mockCache.getCachedPurchaserInfo(appUserId)
        }
        assertThat(receivedInfo).isEqualTo(info)
    }

    @Test
    fun `DEPRECATED when multiple make purchase callbacks, a failure doesn't throw ConcurrentModificationException`() {
        setup()

        val activity: Activity = mockk()

        purchases.makePurchaseWith(
            activity,
            "onemonth_freetrial",
            BillingClient.SkuType.SUBS
        ) { _, _ -> }

        purchases.makePurchaseWith(
            activity,
            "annual_freetrial",
            BillingClient.SkuType.SUBS
        ) { _, _ -> }

        try {
            capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(emptyList(), 0, "fail")
        } catch (e: ConcurrentModificationException) {
            fail("Test throws ConcurrentModificationException")
        }
    }

    @Test
    fun `when multiple make purchase callbacks, a failure doesn't throw ConcurrentModificationException`() {
        setup()

        val activity: Activity = mockk()

        purchases.makePurchaseWith(
            activity,
            mockk<SkuDetails>().also {
                every { it.sku } returns "sku"
            }
        ) { _, _ -> }

        purchases.makePurchaseWith(
            activity,
            mockk<SkuDetails>().also {
                every { it.sku } returns "sku"
            }
        ) { _, _ -> }

        try {
            capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(emptyList(), 0, "fail")
        } catch (e: ConcurrentModificationException) {
            fail("Test throws ConcurrentModificationException")
        }
    }

    @Test
    fun `when checking if Billing is supported, an OK response when starting connection means it's supported`() {
        setup()
        var receivedIsBillingSupported = false
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponse.OK)
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isTrue()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if Billing is supported, disconnections mean billing is not supported`() {
        setup()
        var receivedIsBillingSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingServiceDisconnected()
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if Billing is supported, a non OK response when starting connection means it's not supported`() {
        setup()
        var receivedIsBillingSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED)
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if feature is supported, an OK response when starting connection means it's supported`() {
        setup()
        var featureSupported = false
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        every { mockLocalBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS) } returns BillingClient.BillingResponse.OK
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponse.OK)
        AssertionsForClassTypes.assertThat(featureSupported).isTrue()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if feature is supported, disconnections mean billing is not supported`() {
        setup()
        var featureSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingServiceDisconnected()
        AssertionsForClassTypes.assertThat(featureSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if feature is supported, a non OK response when starting connection means it's not supported`() {
        setup()
        var featureSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        every {
            mockLocalBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED)
        AssertionsForClassTypes.assertThat(featureSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when no play services, feature is not supported`() {
        setup()
        var featureSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        every {
            mockLocalBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        every { mockLocalBillingClient.endConnection() } throws mockk<IllegalArgumentException>()
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED)
        AssertionsForClassTypes.assertThat(featureSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }


    @Test
    fun `when no play services, billing is not supported`() {
        setup()
        var receivedIsBillingSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        every { mockLocalBillingClient.endConnection() } throws mockk<IllegalArgumentException>()
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED)
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when finishTransactions is set to false, do not consume purchases`() {
        setup()
        purchases.finishTransactions = false
        val p: Purchase = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(p))

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                any()
            )
        }
        verify (exactly = 0){
            mockBillingWrapper.consumePurchase(eq(purchaseToken))
        }
    }

    @Test
    fun `when finishTransactions is set to false, don't consume subscriptions on 40x`() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError, Boolean) -> Unit>().captured.invoke(
                PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                true
            )
        }

        setup()

        purchases.finishTransactions = false

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(mockk<Purchase>().also {
            every {
                it.sku
            } returns sku
            every {
                it.purchaseToken
            } returns purchaseToken
        }))

        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(eq(purchaseToken))
        }
    }

    @Test
    fun `when finishTransactions is set to false, don't consume subscriptions on 50x`() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(appUserId),
                eq(sku),
                eq(false),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError, Boolean) -> Unit>().captured.invoke(
                PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                true
            )
        }

        setup()

        purchases.finishTransactions = false

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(mockk<Purchase>().also {
            every {
                it.sku
            } returns sku
            every {
                it.purchaseToken
            } returns purchaseToken
        }))

        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(eq(purchaseToken))
        }
    }

    @Test
    fun `syncing transactions gets whole history and posts it to backend`() {
        setup()

        val p: Purchase = mockk(relaxed = true)
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(listOf(p))
        }

        purchases.syncPurchases()

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(purchases.appUserID),
                eq(sku),
                eq(false),
                any(),
                any()
            )
        }
    }

    @Test
    fun `syncing transactions respects allow sharing account settings`() {
        setup()

        val p: Purchase = mockk(relaxed = true)
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        purchases.allowSharingPlayStoreAccount = true
        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(listOf(p))
        }

        purchases.syncPurchases()

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(purchases.appUserID),
                eq(sku),
                eq(true),
                any(),
                any()
            )
        }
    }

    @Test
    fun `syncing transactions never consumes transactions`() {
        setup()

        val p: Purchase = mockk(relaxed = true)
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        purchases.allowSharingPlayStoreAccount = true
        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken

        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(listOf(p))
        }

        purchases.syncPurchases()

        verify {
            mockBackend.postReceiptData(
                eq(purchaseToken),
                eq(purchases.appUserID),
                eq(sku),
                eq(true),
                any(),
                any()
            )
        }
        verify (exactly = 0){
            mockBillingWrapper.consumePurchase(eq(purchaseToken))
        }
    }

    @Test
    fun `Data is successfully postponed if no instance is set`() {
        val jsonObject = JSONObject()
        val network = Purchases.AttributionNetwork.APPSFLYER

        every {
            mockBackend.postAttributionData(appUserId, network, jsonObject, captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        Purchases.addAttributionData(jsonObject, network, networkUserID)

        mockAdInfo(false, networkUserID)

        setup()

        verify { mockBackend.postAttributionData(eq(appUserId), eq(network), eq(jsonObject), any()) }
    }

    @Test
    fun `Data is successfully postponed if no instance is set when sending map`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)
        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(eq(appUserId), eq(network), any(), any())
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
    }

    @Test
    fun `GPS ID is automatically added`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(
                eq(appUserId),
                eq(network),
                any(),
                any()
            )
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.get("rc_gps_adid")).isEqualTo(adID)
    }

    @Test
    fun `GPS ID is not added if limited`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(true, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(eq(appUserId), eq(network), any(), any())
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.has("rc_gps_adid")).isFalse()
    }

    @Test
    fun `GPS ID is not added if not present`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(true, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(
                eq(appUserId),
                eq(network),
                any(),
                any()
            )
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.has("rc_gps_adid")).isFalse()
    }

    @Test
    fun `do not resend last attribution data to backend`() {
        setup()

        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        every {
            mockCache.getCachedAttributionData(Purchases.AttributionNetwork.APPSFLYER, appUserId)
        } returns "${adID}_networkUserID"

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        verify (exactly = 0){
            mockBackend.postAttributionData(appUserId, network, any(), any())
        }
    }

    @Test
    fun `cache last sent attribution data`() {
        setup()

        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkid"
        mockAdInfo(false, networkUserID)

        every {
            mockCache.getCachedAttributionData(Purchases.AttributionNetwork.APPSFLYER, appUserId)
        } returns null

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        verify (exactly = 1){
            mockBackend.postAttributionData(appUserId, network, any(), any())
        }

        verify (exactly = 1){
            mockCache.cacheAttributionData(network, appUserId, "${adID}_$networkUserID")
        }
    }

    @Test
    fun `network ID is set`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(eq(appUserId), eq(network), any(), any())
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.get("rc_attribution_network_id")).isEqualTo(networkUserID)
        assertThat(capturedJSONObject.captured.has("rc_gps_adid")).isTrue()
    }

    @Test
    fun `caches are not cleared if update purchaser info fails`() {
        val mockInfo = mockk<PurchaserInfo>()

        mockCache(mockInfo)
        with(mockBackend) {
            val purchasesError = PurchasesError(PurchasesErrorCode.StoreProblemError, "Broken")
            every {
                getPurchaserInfo(any(), any(), captureLambda())
            } answers {
                lambda<(PurchasesError) -> Unit>().captured.invoke(purchasesError)
            }
            every {
                getEntitlements(any(), captureLambda(), any())
            } answers {
                lambda<(Map<String, Entitlement>) -> Unit>().captured.invoke(
                    mapOf(
                        "entitlement" to Entitlement(mapOf("sku" to Offering("sku")))
                    )
                )
            }
            every {
                postReceiptData(any(), any(), any(), any(), captureLambda(), any())
            } answers {
                lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
            }
            every {
                close()
            } just Runs
        }
        mockBillingWrapper()
        every {
            listener.onReceived(any())
        } just Runs

        purchases = Purchases(
            mockContext,
            appUserId,
            mockBackend,
            mockBillingWrapper,
            mockCache
        )
        Purchases.sharedInstance = purchases

        verify (exactly = 0) { mockCache.clearCachedPurchaserInfo(any()) }
    }

    // region Private Methods
    private fun mockSkuDetailFetch(details: List<SkuDetails>, skus: List<String>, skuType: String) {
        every {
            mockBillingWrapper.querySkuDetailsAsync(
                eq(skuType),
                eq(skus),
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<SkuDetails>) -> Unit>().captured.invoke(details)
        }
    }

    private fun mockBillingWrapper() {
        with(mockBillingWrapper) {
            every {
                querySkuDetailsAsync(any(), any(), any(), any())
            } just Runs
            every {
                makePurchaseAsync(any(), any(), any(), any(), any())
            } just Runs
            every {
                makePurchaseAsync(any(), any(), any(), any())
            } just Runs
            every {
                purchasesUpdatedListener = capture(capturedPurchasesUpdatedListener)
            } just Runs
            every {
                consumePurchase(any())
            } just Runs
            every {
                purchasesUpdatedListener = null
            } just Runs
        }
    }

    private fun mockBackend(mockInfo: PurchaserInfo) {
        with(mockBackend) {
            every {
                getPurchaserInfo(any(), captureLambda(), any())
            } answers {
                lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
            }
            every {
                getEntitlements(any(), captureLambda(), any())
            } answers {
                lambda<(Map<String, Entitlement>) -> Unit>().captured.invoke(
                    mapOf(
                        "entitlement" to Entitlement(mapOf("sku" to Offering("sku")))
                    )
                )
            }
            every {
                postReceiptData(any(), any(), any(), any(), captureLambda(), any())
            } answers {
                lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
            }
            every {
                close()
            } just Runs
        }
    }

    private fun mockCache(mockInfo: PurchaserInfo) {
        with(mockCache) {
            every {
                getCachedAppUserID()
            } returns null
            every {
                getCachedPurchaserInfo(any())
            } returns mockInfo
            every {
                cachePurchaserInfo(any(), any())
            } just Runs
            every {
                cacheAppUserID(any())
            } just Runs
            every {
                clearCachedPurchaserInfo(any())
            } just Runs
            every {
                mockCache.getCachedAttributionData(Purchases.AttributionNetwork.APPSFLYER, appUserId)
            } returns null
        }
    }

    private fun mockProducts(skus: List<String>) {
        every {
            mockBackend.getEntitlements(any(), captureLambda(), any())
        } answers {
            val offeringMap = skus.map { sku -> sku + "_offering" to Offering(sku) }.toMap()
            lambda<(Map<String, Entitlement>) -> Unit>().captured.invoke(
                mapOf(
                    "pro" to Entitlement(
                        offeringMap
                    )
                )
            )
        }
    }

    private fun mockSkuDetails(
        skus: List<String>,
        returnSkus: List<String>,
        type: String
    ): List<SkuDetails> {
        val skuDetailsList = returnSkus.map { sku ->
            mockk<SkuDetails>().also {
                every { it.sku } returns sku
            }
        }

        mockSkuDetailFetch(skuDetailsList, skus, type)
        return skuDetailsList
    }

    private fun mockCloseActions() {
        every {
            mockBackend.close()
        } just Runs
        every {
            mockBillingWrapper.purchasesUpdatedListener = null
        } just Runs
    }

    private fun mockAdInfo(limitAdTrackingEnabled: Boolean, networkUserID: String) {
        val adInfo = mockk<AdvertisingIdClient.AdInfo>()
        every { adInfo.isLimitAdTrackingEnabled } returns limitAdTrackingEnabled
        every { adInfo.id } returns if (!limitAdTrackingEnabled) adID else ""

        mockkObject(AdvertisingIdClient)

        val lst = slot<(AdvertisingIdClient.AdInfo?) -> Unit>()
        every {
            AdvertisingIdClient.getAdvertisingIdInfo(any(), capture(lst))
        } answers {
            lst.captured.invoke(adInfo)
        }

        every {
            mockCache.cacheAttributionData(
                Purchases.AttributionNetwork.APPSFLYER,
                appUserId,
                listOfNotNull(adID.takeUnless { limitAdTrackingEnabled }, networkUserID).joinToString("_")
            )
        } just Runs
    }
    // endregion
}

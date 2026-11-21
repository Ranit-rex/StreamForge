package app.streamforge.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

private const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

@Composable
fun RewardedAdsPanel() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var rewardedAd by remember { mutableStateOf<RewardedAd?>(null) }
    var status by remember { mutableStateOf("Preparing Google test ad…") }
    var earnedRewards by remember { mutableIntStateOf(0) }

    fun loadRewardedAd() {
        status = "Loading test rewarded ad…"
        RewardedAd.load(
            context,
            TEST_REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    status = "Test ad ready"
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    status = "Ad unavailable: ${error.message}"
                }
            }
        )
    }

    LaunchedEffect(Unit) { loadRewardedAd() }

    Surface(
        color = Color(0xFF121923),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("REWARDED ADS · TEST", color = Color(0xFF9DB4FF), fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Text("Try a rewarded video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("This uses Google's official test ad unit only. No real ad revenue or account reward is generated.", color = Color(0xFF91A0B4), fontSize = 11.sp)
            Text(status, color = Color(0xFF91A0B4), fontSize = 11.sp)
            Button(
                onClick = {
                    val ad = rewardedAd
                    val host = activity
                    if (ad != null && host != null) {
                        rewardedAd = null
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdShowedFullScreenContent() {
                                status = "Test ad is playing"
                            }

                            override fun onAdDismissedFullScreenContent() {
                                status = "Ad closed · loading another test ad…"
                                loadRewardedAd()
                            }

                            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                                status = "Could not show ad: ${error.message}"
                                loadRewardedAd()
                            }
                        }
                        ad.show(host) {
                            earnedRewards += 1
                            status = "Test reward received"
                        }
                    } else {
                        status = "Ad is not ready yet"
                        if (rewardedAd == null) loadRewardedAd()
                    }
                },
                enabled = rewardedAd != null && activity != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9DB4FF), contentColor = Color(0xFF101827))
            ) {
                Text("▶  WATCH TEST AD", fontWeight = FontWeight.Black)
            }
            Text("Test rewards received: $earnedRewards", color = Color(0xFF55D6A1), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

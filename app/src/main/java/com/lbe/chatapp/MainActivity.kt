package com.lbe.chatapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lbe.imsdk.LbeSdk
import com.lbe.imsdk.ui.theme.ChatAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            ChatAppTheme {
                NickIdPrompt { nickId, nickName, lbeIdentity, lbeSign, phone, email, language, device, headerIcon, groupID, domain ->
                    LbeSdk.init(
                        context = context,
                        lbeSign = lbeSign,
                        lbeIdentity = lbeIdentity,
                        nickId = nickId,
                        nickName = nickName,
                        phone = phone,
                        email = email,
                        language = language,
                        device = device,
                        headerIcon = headerIcon,
                        groupID = groupID,
                        source = "",
                        domain = domain,
                    )
                    finish()
                }
            }
        }
    }
}

@Composable
fun NickIdPrompt(
    onStart: (
        nickId: String,
        nickName: String,
        lbeIdentity: String,
        lbeSign: String,
        phone: String,
        email: String,
        language: String,
        device: String,
        headerIcon: String,
        groupID: String,
        domain: String,
    ) -> Unit,
) {
//    val domain = remember { mutableStateOf("https://4jlfe1imqsee.imsz.online") }
//    val domain = remember { mutableStateOf("http://4l6li7zz5lr8.imgo.sz") }
    //https://4qar56cuf77q.aisz.org/customer-list
    val domain = remember { mutableStateOf("https://4qar56cuf77q.imsz.online") }
    // HermitK1
    var nickId by remember { mutableStateOf("") }
//    var nickId by remember { mutableStateOf("") }
    var nickName by remember { mutableStateOf("") }
//    var nickId by remember { mutableStateOf("android001") }
//    var nickName by remember { mutableStateOf("android001") }

    // dev_my
//    var lbeSign by remember { mutableStateOf("0x77ce23dc4033c7e3b6cd9ec78b5c1d365ac8f4076e443575bb918451b63614011c7da66897248caebdb466b6ccb832aa639b0ddc5fe2574915759bbd5710b7aa1c") }
//    var lbeIdentity by remember { mutableStateOf("46gz1ezu9rd5") }

    // sit
//    var lbeSign by remember { mutableStateOf("0x9d63fcec00dffa1e7bbebfa4f0afa80f5f26614613b29357d580b69b708d2d893b6eef2d013828830f9c52f647edcd9ebc5ec73900d178b4c1a27732fb24cefe1b") }
//    var lbeIdentity by remember { mutableStateOf("441zy52mn2yy") }

    // uat_test
    var lbeSign by remember {
        mutableStateOf(
//            "0x49ca5e1d651d4fbff606d0efb2800822699e17ef972708ccaf95f5c41eb4ce1b39d02d467fa3db27bd129dad648cbfe8cc5f34f4cf54fe933b78205d19b0a17a1c",
//            "0xbb48b4f8b551212b9831b975f9d05faf210e0809f68cccf84d6f813354c2c8340e150f539bb38926c1384ec347231d2c761e94d2fadd84853d57cb0aaabea3041b",
            "0x10b2c9bd35c3675cd895705c6288aafad4ff44a838ff55a3f9ea75c033f1ba9d2094e4a3f68e83e52edfe7861ce91cee6de1824fef1b8b86c9f2f28b321847271c",
        )
    }
//    var lbeIdentity by remember { mutableStateOf("4l6li7zz5lr8") }
    var lbeIdentity by remember { mutableStateOf("4qar56cuf77q") }
//    var lbeSign by remember { mutableStateOf("0x4f227352cf96fab9e67064e08219a86cd398fdbb067aa53fc7ad49deb882a0ad49b1d073ae0b3f74d39d288f3cf3feab6f102c1993532e1239e2f48e4afb534b1c") }
//    var lbeIdentity by remember { mutableStateOf("46gytl9ojaft") }

    // test
//    var lbeSign by remember { mutableStateOf("0x281d5af2a0b222d5e0b99773372c1a4b955fbde587de0b1fd3708586b92020dc3437f9fff59e4f24c6aaca2b8a44d1ff82638e131c6a2d2c35289624e89b11c61b") }
//    var lbeIdentity by remember { mutableStateOf("46igleuw9olm") }
//    android001

//    android001

    var phone by remember { mutableStateOf("") }

    var email by remember { mutableStateOf("") }

    var language by remember { mutableStateOf("zh") }

    var device by remember { mutableStateOf("") }

    var headerIcon by remember {
        mutableStateOf("{\"url\":\"https://abpay-pub.s3.ap-northeast-1.amazonaws.com/1078_1724306513153.png\",\"key\":\"\"}")
    }
//    var headerIcon by remember { mutableStateOf("") }

    var groupID by remember { mutableStateOf("1001") }

//    var source by remember { mutableStateOf("Android") }

    Card {
        LazyColumn(
            modifier =
                Modifier
                    .padding(8.dp)
                    .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                OutlinedTextField(
                    value = domain.value,
                    onValueChange = { domain.value = it },
                    label = { Text(text = "domain") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = lbeSign,
                    onValueChange = { lbeSign = it },
                    label = { Text(text = "lbeSign") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = headerIcon,
                    onValueChange = { headerIcon = it },
                    label = { Text(text = "avatar") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = lbeIdentity,
                    onValueChange = { lbeIdentity = it },
                    label = { Text(text = "LbeIdentity") },
                    readOnly = false,
                )
            }

            item {
                OutlinedTextField(
                    value = nickId,
                    onValueChange = { nickId = it },
                    label = { Text(text = "NickId") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = nickName,
                    onValueChange = { nickName = it },
                    label = { Text(text = "NickName") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(text = "phone") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(text = "email") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text(text = "language") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = device,
                    onValueChange = { device = it },
                    label = { Text(text = "device") },
                    readOnly = false,
                )
            }
            item {
                OutlinedTextField(
                    value = groupID,
                    onValueChange = { groupID = it },
                    label = { Text(text = "groupID") },
                    readOnly = false,
                )
            }

            item {
                Button(onClick = {
                    onStart(
                        nickId,
                        nickName,
                        lbeIdentity,
                        lbeSign,
                        phone,
                        email,
                        language,
                        device,
                        headerIcon,
                        groupID,
                        domain.value,
                    )
                }) {
                    Text(text = "Connect")
                }
            }
        }
    }
}

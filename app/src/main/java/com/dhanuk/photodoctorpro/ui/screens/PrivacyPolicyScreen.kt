package com.dhanuk.photodoctorpro.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun PrivacyPolicyScreen(navController: NavController) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Privacy Policy") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                Text(
                    """
                    Privacy Policy for PhotoDoctor Pro

                    This Privacy Policy describes how your personal information is handled in PhotoDoctor Pro.

                    **1. Information We Collect**
                    PhotoDoctor Pro is designed to respect your privacy. All image processing is done entirely on your device. We do not collect, store, or transmit any of your photos or personal information.

                    **2. On-Device Processing**
                    All features of PhotoDoctor Pro, including background removal, object erasing, and image enhancement, are performed on your device. Your photos are never uploaded to a server.

                    **3. Advertising**
                    PhotoDoctor Pro uses Google AdMob to display ads. AdMob may collect and use anonymous data for advertising purposes, such as your device's advertising ID. We do not share any personal information with AdMob.

                    **4. Analytics**
                    We do not collect any analytics data.

                    **5. Changes to This Privacy Policy**
                    We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page.

                    **6. Contact Us**
                    If you have any questions about this Privacy Policy, please contact us at support@dhanuksoftware.com.
                    """.trimIndent()
                )
            }
        }
    }
}

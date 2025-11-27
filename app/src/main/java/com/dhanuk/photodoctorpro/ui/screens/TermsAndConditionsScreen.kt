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
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsAndConditionsScreen(navController: NavController) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Terms & Conditions") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                Text(
                    """
                    Terms & Conditions for PhotoDoctor Pro

                    By downloading or using PhotoDoctor Pro, these terms will automatically apply to you.

                    **1. Use of the App**
                    You are permitted to use PhotoDoctor Pro for your personal, non-commercial use.

                    **2. Intellectual Property**
                    The app and all its content, features, and functionality are owned by Dhanuk Software.

                    **3. Disclaimer of Warranties**
                    The app is provided "as is," without warranty of any kind.

                    **4. Limitation of Liability**
                    In no event shall Dhanuk Software be liable for any damages arising out of the use or inability to use the app.

                    **5. Changes to These Terms**
                    We may update our Terms and Conditions from time to time. We will notify you of any changes by posting the new Terms and Conditions on this page.

                    **6. Contact Us**
                    If you have any questions about these Terms and Conditions, please contact us at support@dhanuksoftware.com.
                    """.trimIndent()
                )
            }
        }
    }
}

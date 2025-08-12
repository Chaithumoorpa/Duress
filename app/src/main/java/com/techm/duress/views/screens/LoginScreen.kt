package com.techm.duress.views.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.views.widgets.CustomButton

@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val keyboard = LocalSoftwareKeyboardController.current

    var name by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }
    val isError = touched && name.isBlank()
    val isSubmitEnabled = name.trim().isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (!touched) touched = true
            },
            label = { Text("Name") },
            placeholder = { Text("Enter your name") },
            singleLine = true,
            isError = isError,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (isError) {
            Text(
                text = "Name is required",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(top = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        CustomButton(
            text = "Submit",
            backgroundColor = MaterialTheme.colorScheme.primary,
            onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    touched = true
                    return@CustomButton
                }
                keyboard?.hide()
                viewModel.setUserInfo(trimmed)
                // Encode to keep route safe (spaces, symbols)
                val encoded = Uri.encode(trimmed)
                navController.navigate("home_screen/$encoded")
            },
            height = 50.dp,
            enabled = isSubmitEnabled
        )
    }
}

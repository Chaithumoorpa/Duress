package com.techm.duress.views.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.text.font.FontWeight
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.views.widgets.CustomButton

@Composable
fun LoginScreen(navController: NavController, viewModel: MainViewModel) {
    var name by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )
//        TextField(value = name, onValueChange = { name = it }, label = { Text("Name") })

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            placeholder = { Text("Enter your name") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp), // Rounded rectangle corners
            modifier = Modifier
                .fillMaxWidth(),
//            colors = TextFieldDefaults.outlinedTextFieldColors(
//                focusedBorderColor = Color(0xFF6200EE),
//                unfocusedBorderColor = Color.Gray,
//                focusedLabelColor = Color(0xFF6200EE)
//            )
        )

        //TextField(value = zone, onValueChange = { zone = it }, label = { Text("Zone") })
        //Spacer(modifier = Modifier.height(24.dp))
//        Button(onClick = {
//            viewModel.setUserInfo(name, zone)
//            navController.navigate("home_screen/$name/$zone")
//        }) {
//            Text("Submit")
//        }
        CustomButton(
            onClick = {
                viewModel.setUserInfo(name)
                navController.navigate("home_screen/$name")
            },
            backgroundColor = Color.Blue,
            text = "Submit",
            height = 50.dp
        )
    }
}

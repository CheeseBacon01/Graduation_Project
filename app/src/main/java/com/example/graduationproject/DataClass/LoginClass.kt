package com.example.graduationproject.DataClass

class LoginClass {

}
data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val success: Boolean,
    val message: String,
    val data: UserData?
)

data class UserData(
    val account_id: Int,
    val role: String
)
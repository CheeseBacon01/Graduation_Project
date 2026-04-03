package com.example.graduationproject.api

import com.example.graduationproject.DataClass.LoginRequest
import com.example.graduationproject.DataClass.LoginResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("api/login.php")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
}
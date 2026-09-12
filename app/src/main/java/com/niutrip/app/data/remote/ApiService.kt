package com.niutrip.app.data.remote

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("auth/register/") suspend fun register(@Body body: RegisterIn): LoginOut
    @POST("auth/login/") suspend fun login(@Body body: LoginIn): LoginOut
    @GET("user/profile/") suspend fun profile(): UserDto
    @PUT("user/profile/") suspend fun updateProfile(@Body body: ProfileUpdateIn): UserDto
    @PUT("user/password/") suspend fun changePassword(@Body body: PasswordIn): DetailOut
    @GET("user/bindings/") suspend fun bindings(): UserDto
    @PUT("user/bindings/") suspend fun updateBindings(@Body body: BindingsIn): UserDto
    @Multipart @POST("upload/image") suspend fun upload(@Part image: MultipartBody.Part): UploadOut
    @GET("tracks/") suspend fun tracks(@Query("scope") scope: String = "mine"): List<TrackDto>
    @POST("tracks/") suspend fun createTrack(@Body body: TrackCreateIn): TrackDto
    @GET("tracks/{trackId}/") suspend fun track(@Path("trackId") id: String): TrackDto
    @PATCH("tracks/{trackId}/") suspend fun patchTrack(@Path("trackId") id: String, @Body body: TrackPatchIn): TrackDto
    @Multipart @PUT("tracks/{trackId}/cover/") suspend fun updateTrackCover(@Path("trackId") id: String, @Part image: MultipartBody.Part): TrackDto
    @DELETE("tracks/{trackId}/") suspend fun deleteTrack(@Path("trackId") id: String)
    @DELETE("tracks/{trackId}/received-share/") suspend fun deleteReceivedShare(@Path("trackId") id: String)
    @POST("tracks/{trackId}/points/") suspend fun postPoints(@Path("trackId") id: String, @Body body: PointsIn): PostPointsOut
    @GET("tracks/{trackId}/points/") suspend fun points(@Path("trackId") id: String, @Query("page") page: Int = 1): PointsPage
    @PATCH("tracks/{trackId}/points/{pointId}/") suspend fun patchPoint(@Path("trackId") trackId: String, @Path("pointId") pointId: String, @Body body: PointPatchIn): PointDto
    @POST("tracks/{trackId}/share/") suspend fun share(@Path("trackId") id: String, @Body body: ShareIn): ShareOut
    @DELETE("tracks/{trackId}/share/") suspend fun revokeShare(@Path("trackId") id: String): Response<Unit>
    @GET("share/{token}/inspect/") suspend fun inspectShare(@Path("token") token: String): ShareInspectOut
    @GET("share/{token}/") suspend fun shareView(@Path("token") token: String): ShareDataDto
    @POST("share/{token}/save/") suspend fun shareSave(@Path("token") token: String): ShareDataDto
}

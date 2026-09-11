package com.niutrip.app.data.remote

import okhttp3.MultipartBody
import retrofit2.Response

/** 测试基座：默认全部 TODO，测试里只 override 用到的方法。 */
open class StubApi : ApiService {
    override suspend fun register(body: RegisterIn): LoginOut = TODO()
    override suspend fun login(body: LoginIn): LoginOut = TODO()
    override suspend fun profile(): UserDto = TODO()
    override suspend fun updateProfile(body: ProfileUpdateIn): UserDto = TODO()
    override suspend fun changePassword(body: PasswordIn): DetailOut = TODO()
    override suspend fun bindings(): UserDto = TODO()
    override suspend fun updateBindings(body: BindingsIn): UserDto = TODO()
    override suspend fun upload(image: MultipartBody.Part): UploadOut = TODO()
    override suspend fun tracks(scope: String): List<TrackDto> = TODO()
    override suspend fun createTrack(body: TrackCreateIn): TrackDto = TODO()
    override suspend fun track(id: String): TrackDto = TODO()
    override suspend fun patchTrack(id: String, body: TrackPatchIn): TrackDto = TODO()
    override suspend fun deleteTrack(id: String): Response<Unit> = TODO()
    override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut = TODO()
    override suspend fun points(id: String, page: Int): PointsPage = TODO()
    override suspend fun share(id: String, body: ShareIn): ShareOut = TODO()
    override suspend fun revokeShare(id: String): Response<Unit> = TODO()
    override suspend fun shareView(token: String): ShareDataDto = TODO()
    override suspend fun shareSave(token: String): ShareDataDto = TODO()
}

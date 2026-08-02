package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.AddCategoryRequest
import com.chunland.app.data.model.CategorySchemePage
import com.chunland.app.data.model.CategoryScheme
import com.chunland.app.data.model.CreatePostRequest
import com.chunland.app.data.model.CreateProductRequest
import com.chunland.app.data.model.CreateSchemeRequest
import com.chunland.app.data.model.CreatedCategory
import com.chunland.app.data.model.MerchantOrderDetail
import com.chunland.app.data.model.MerchantOrderPage
import com.chunland.app.data.model.MerchantPost
import com.chunland.app.data.model.MerchantPostPage
import com.chunland.app.data.model.MerchantProduct
import com.chunland.app.data.model.MerchantProductPage
import com.chunland.app.data.model.MerchantStats
import com.chunland.app.data.model.MyStore
import com.chunland.app.data.model.NameRequest
import com.chunland.app.data.model.SchemeCategoryProducts
import com.chunland.app.data.model.SetCategoryProductsRequest
import com.chunland.app.data.model.SetProductImagesRequest
import com.chunland.app.data.model.UpdateProductRequest
import com.chunland.app.data.model.UpdateSchemeRequest
import com.chunland.app.data.model.UpdateStoreRequest
import com.chunland.app.data.model.UploadedImage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 商家自助控制台（merchants/self 系列端点，均需 merchant 角色）。
 * 开店端点不在这里 —— POST merchants/self 要重签 token，归 AuthApi/AuthManager 管。
 */
interface MerchantConsoleApi {

    @GET("merchants/self")
    suspend fun myStore(): ApiEnvelope<MyStore>

    /** 店铺设置：名称 / 发货地（改后报价即时生效）/ 起送金额。null 字段 = 不改 */
    @PATCH("merchants/self")
    suspend fun updateStore(@Body body: UpdateStoreRequest): ApiEnvelope<MyStore>

    // ---- 订单只读视图----

    @GET("merchants/self/orders")
    suspend fun orders(@Query("status") status: String? = null): ApiEnvelope<MerchantOrderPage>

    @GET("merchants/self/orders/{id}")
    suspend fun order(@Path("id") id: Int): ApiEnvelope<MerchantOrderDetail>

    // ---- 商品管理（自建商品 code 由服务端生成，M<merchantId>-<hex>）----

    @GET("merchants/self/products")
    suspend fun products(): ApiEnvelope<MerchantProductPage>

    @POST("merchants/self/products")
    suspend fun createProduct(@Body body: CreateProductRequest): ApiEnvelope<MerchantProduct>

    /** 改名/改价/改描述/上下架。null 字段 = 不改 */
    @PATCH("merchants/self/products/{code}")
    suspend fun updateProduct(
        @Path("code") code: String,
        @Body body: UpdateProductRequest,
    ): ApiEnvelope<MerchantProduct>

    // ---- 图片（raw 二进制与凭证同口径：body 为原始字节，Content-Type 随文件）----

    /** 店铺 logo，上传即生效（merchant 公开桶 + merchants.logo_url） */
    @POST("merchants/self/logo")
    suspend fun uploadLogo(@Body body: RequestBody): ApiEnvelope<MyStore>

    /** 商品图两步式第一步：传图拿 key（建品前即可传） */
    @POST("merchants/self/product-images")
    suspend fun uploadProductAsset(@Body body: RequestBody): ApiEnvelope<UploadedImage>

    /** 商品图两步式第二步：整体替换（keys[0] 主图，最多 9 张） */
    @PUT("merchants/self/products/{code}/images")
    suspend fun setProductImages(
        @Path("code") code: String,
        @Body body: SetProductImagesRequest,
    ): ApiEnvelope<MerchantProduct>

    // ---- 店铺动态：自发图文进 feed_items，接入现有发现/关注流 ----

    @GET("merchants/self/posts")
    suspend fun posts(): ApiEnvelope<MerchantPostPage>

    /** 动态配图（与商品图同桶不同前缀），返回 key 供 createPost 引用 */
    @POST("merchants/self/post-images")
    suspend fun uploadPostImage(@Body body: RequestBody): ApiEnvelope<UploadedImage>

    @POST("merchants/self/posts")
    suspend fun createPost(@Body body: CreatePostRequest): ApiEnvelope<MerchantPost>

    /** 软删（与 feed 全局约定一致） */
    @DELETE("merchants/self/posts/{id}")
    suspend fun deletePost(@Path("id") id: Int): ApiEnvelope<JsonElement>

    // ---- 分类方案（lens）：上限每店 10 方案、每方案 30 分类、每分类 500 商品（服务端校验）----

    /** 我的方案（含隐藏 + 分类 + 商品数） */
    @GET("merchants/self/schemes")
    suspend fun schemes(): ApiEnvelope<CategorySchemePage>

    @POST("merchants/self/schemes")
    suspend fun createScheme(@Body body: CreateSchemeRequest): ApiEnvelope<CategoryScheme>

    /** 改名 / 显隐 / 设默认（设默认服务端自动清旧默认） */
    @PATCH("merchants/self/schemes/{id}")
    suspend fun updateScheme(
        @Path("id") id: Int,
        @Body body: UpdateSchemeRequest,
    ): ApiEnvelope<CategoryScheme>

    /** 删方案（级联删分类与归属） */
    @DELETE("merchants/self/schemes/{id}")
    suspend fun deleteScheme(@Path("id") id: Int): ApiEnvelope<JsonElement>

    /** 加分类：parentId 非空 = 建二级（挂该一级下） */
    @POST("merchants/self/schemes/{id}/categories")
    suspend fun addSchemeCategory(
        @Path("id") schemeId: Int,
        @Body body: AddCategoryRequest,
    ): ApiEnvelope<CreatedCategory>

    @PATCH("merchants/self/scheme-categories/{catId}")
    suspend fun renameSchemeCategory(
        @Path("catId") catId: Int,
        @Body body: NameRequest,
    ): ApiEnvelope<JsonElement>

    /** re-parent（改层级）：body { parentId } —— null=升为一级，数字=挂到该一级下。
     *  用 JsonObject 恒发 parentId（含 JsonNull），绕开 ChunlandJson 的 explicitNulls=false。 */
    @PATCH("merchants/self/scheme-categories/{catId}")
    suspend fun moveSchemeCategory(
        @Path("catId") catId: Int,
        @Body body: JsonObject,
    ): ApiEnvelope<JsonElement>

    @DELETE("merchants/self/scheme-categories/{catId}")
    suspend fun deleteSchemeCategory(@Path("catId") catId: Int): ApiEnvelope<JsonElement>

    /** 分类下商品（编辑器读） */
    @GET("merchants/self/scheme-categories/{catId}/products")
    suspend fun categoryProducts(@Path("catId") catId: Int): ApiEnvelope<SchemeCategoryProducts>

    /** 分类下商品整体替换（编辑器保存语义，校验全部属于本店） */
    @PUT("merchants/self/scheme-categories/{catId}/products")
    suspend fun setCategoryProducts(
        @Path("catId") catId: Int,
        @Body body: SetCategoryProductsRequest,
    ): ApiEnvelope<SchemeCategoryProducts>

    // ---- 经营数据（纯读）----

    @GET("merchants/self/stats")
    suspend fun stats(): ApiEnvelope<MerchantStats>
}

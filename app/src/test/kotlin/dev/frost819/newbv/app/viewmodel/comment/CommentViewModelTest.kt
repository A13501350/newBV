package dev.frost819.newbv.app.viewmodel.comment

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.comment.Comment
import dev.frost819.newbv.biliapi.entity.comment.CommentPage
import dev.frost819.newbv.biliapi.repositories.CommentRepository
import dev.frost819.newbv.data.repository.AccountRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [CommentViewModel] 的排序缓存、分页、楼中楼、点赞与回复发表测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: CommentRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var viewModel: CommentViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        accountRepository = mockk()
        every { accountRepository.isLogin() } returns true
        viewModel = CommentViewModel(repository, accountRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load requests hot comments and exposes page`() =
        runTest(dispatcher) {
            val root = comment(rpid = 1L, replyCount = 2)
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(root), page = 1, total = 1, hasMore = false)

            viewModel.load(100L)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.comments).containsExactly(root)
            assertThat(viewModel.uiState.value.hasMore).isFalse()
            coVerify { repository.getComments(100L, 1, 1, null, any()) }
        }

    @Test
    fun `switching sort caches each comment list and does not reload`() =
        runTest(dispatcher) {
            val hot = comment(rpid = 1L)
            val latest = comment(rpid = 2L)
            coEvery { repository.getComments(100L, 1, 1, null, any()) } returns
                page(listOf(hot), page = 1, total = 1, hasMore = false)
            coEvery { repository.getComments(100L, 0, 1, null, any()) } returns
                page(listOf(latest), page = 1, total = 1, hasMore = false)

            viewModel.load(100L)
            advanceUntilIdle()

            viewModel.changeSort(CommentSort.Latest)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.comments).containsExactly(latest)

            viewModel.changeSort(CommentSort.Hot)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.comments).containsExactly(hot)

            viewModel.changeSort(CommentSort.Latest)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.comments).containsExactly(latest)

            coVerify(exactly = 1) { repository.getComments(100L, 1, 1, null, any()) }
            coVerify(exactly = 1) { repository.getComments(100L, 0, 1, null, any()) }
        }

    @Test
    fun `refresh only reloads current sort and keeps other cache`() =
        runTest(dispatcher) {
            val hot = comment(rpid = 1L)
            val latest = comment(rpid = 2L)
            coEvery { repository.getComments(100L, 1, 1, null, any()) } returns
                page(listOf(hot), page = 1, total = 1, hasMore = false)
            coEvery { repository.getComments(100L, 0, 1, null, any()) } returns
                page(listOf(latest), page = 1, total = 1, hasMore = false)

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.changeSort(CommentSort.Latest)
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getComments(100L, 1, 1, null, any()) }
            coVerify(exactly = 2) { repository.getComments(100L, 0, 1, null, any()) }

            viewModel.changeSort(CommentSort.Hot)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.comments).containsExactly(hot)
            coVerify(exactly = 1) { repository.getComments(100L, 1, 1, null, any()) }
        }

    @Test
    fun `changing aid discards stale response`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<CommentPage>()
            coEvery { repository.getComments(1L, 1, 1, null, any()) } coAnswers { gate.await() }
            coEvery { repository.getComments(2L, 1, 1, null, any()) } returns
                page(listOf(comment(rpid = 20L)), page = 1, total = 1, hasMore = false)

            viewModel.load(1L)
            runCurrent()
            viewModel.load(2L)
            advanceUntilIdle()

            gate.complete(page(listOf(comment(rpid = 10L)), page = 1, total = 1, hasMore = false))
            runCurrent()

            assertThat(viewModel.uiState.value.aid).isEqualTo(2L)
            assertThat(
                viewModel.uiState.value.comments
                    .map { it.rpid },
            ).containsExactly(20L)
        }

    @Test
    fun `load more appends and deduplicates by rpid`() =
        runTest(dispatcher) {
            coEvery { repository.getComments(100L, 1, 1, null, any()) } returns
                page(listOf(comment(1L), comment(2L)), page = 1, total = 3, hasMore = true)
            coEvery { repository.getComments(100L, 1, 2, null, any()) } returns
                page(listOf(comment(2L), comment(3L)), page = 2, total = 3, hasMore = false)

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.loadMoreComments()
            advanceUntilIdle()

            assertThat(
                viewModel.uiState.value.comments
                    .map { it.rpid },
            ).containsExactly(1L, 2L, 3L).inOrder()
            assertThat(viewModel.uiState.value.hasMore).isFalse()
        }

    @Test
    fun `load more failure keeps list and allows retry`() =
        runTest(dispatcher) {
            var attempt = 0
            coEvery { repository.getComments(100L, 1, 1, null, any()) } returns
                page(listOf(comment(1L), comment(2L)), page = 1, total = 3, hasMore = true)
            coEvery { repository.getComments(100L, 1, 2, null, any()) } coAnswers {
                attempt++
                if (attempt == 1) throw RuntimeException("boom")
                page(listOf(comment(3L)), page = 2, total = 3, hasMore = false)
            }

            viewModel.load(100L)
            advanceUntilIdle()

            viewModel.loadMoreComments()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.loadMoreError).isTrue()
            assertThat(viewModel.uiState.value.comments).hasSize(2)

            viewModel.loadMoreComments()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.loadMoreError).isFalse()
            assertThat(
                viewModel.uiState.value.comments
                    .map { it.rpid },
            ).containsExactly(1L, 2L, 3L).inOrder()
        }

    @Test
    fun `load more empty page stops pagination`() =
        runTest(dispatcher) {
            coEvery { repository.getComments(100L, 1, 1, null, any()) } returns
                page(listOf(comment(1L)), page = 1, total = 10, hasMore = true)
            coEvery { repository.getComments(100L, 1, 2, null, any()) } returns
                page(emptyList(), page = 2, total = 10, hasMore = true)

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.loadMoreComments()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.hasMore).isFalse()
            assertThat(viewModel.uiState.value.comments).hasSize(1)
        }

    @Test
    fun `toggle replies loads and expands root comment`() =
        runTest(dispatcher) {
            val root = comment(rpid = 1L, replyCount = 1)
            val reply = comment(rpid = 2L, rootRpid = 1L)
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(root), page = 1, total = 1, hasMore = false)
            coEvery { repository.getReplies(any(), any(), any(), any(), any()) } returns
                page(listOf(reply), page = 1, total = 1, hasMore = false)

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.toggleReplies(1L)
            advanceUntilIdle()

            val replyList =
                viewModel.uiState.value.commentList
                    .replyList(1L)
            assertThat(replyList?.expanded).isTrue()
            assertThat(replyList?.replies).containsExactly(reply)
        }

    @Test
    fun `reply first page failure can be retried with one call`() =
        runTest(dispatcher) {
            var attempt = 0
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(comment(rpid = 1L, replyCount = 1)), page = 1, total = 1, hasMore = false)
            coEvery { repository.getReplies(any(), any(), any(), any(), any()) } coAnswers {
                attempt++
                if (attempt == 1) throw RuntimeException("boom")
                page(listOf(comment(rpid = 2L, rootRpid = 1L)), page = 1, total = 1, hasMore = false)
            }

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.toggleReplies(1L)
            advanceUntilIdle()

            assertThat(
                viewModel.uiState.value.commentList
                    .replyList(1L)
                    ?.initialError,
            ).isTrue()

            viewModel.retryReplies(1L)
            advanceUntilIdle()

            val replyList =
                viewModel.uiState.value.commentList
                    .replyList(1L)
            assertThat(replyList?.initialError).isFalse()
            assertThat(replyList?.replies).hasSize(1)
            assertThat(replyList?.expanded).isTrue()
        }

    @Test
    fun `load keeps reply previews from main page`() =
        runTest(dispatcher) {
            val root = comment(rpid = 1L, replyCount = 2)
            val preview = comment(rpid = 11L, rootRpid = 1L)
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(
                    listOf(root),
                    page = 1,
                    total = 1,
                    hasMore = false,
                    replyPreviews = mapOf(1L to listOf(preview)),
                )

            viewModel.load(100L)
            advanceUntilIdle()

            assertThat(
                viewModel.uiState.value.commentList
                    .replyPreview(1L),
            ).containsExactly(preview)
        }

    @Test
    fun `load more merges reply previews of appended comments`() =
        runTest(dispatcher) {
            val first = comment(rpid = 1L, replyCount = 1)
            val second = comment(rpid = 2L, replyCount = 1)
            val firstPreview = comment(rpid = 11L, rootRpid = 1L)
            val secondPreview = comment(rpid = 22L, rootRpid = 2L)
            coEvery { repository.getComments(100L, 1, 1, null, any()) } returns
                page(
                    listOf(first),
                    page = 1,
                    total = 2,
                    hasMore = true,
                    replyPreviews = mapOf(1L to listOf(firstPreview)),
                )
            coEvery { repository.getComments(100L, 1, 2, null, any()) } returns
                page(
                    listOf(second),
                    page = 2,
                    total = 2,
                    hasMore = false,
                    replyPreviews = mapOf(2L to listOf(secondPreview)),
                )

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.loadMoreComments()
            advanceUntilIdle()

            val commentList = viewModel.uiState.value.commentList
            assertThat(commentList.replyPreview(1L)).containsExactly(firstPreview)
            assertThat(commentList.replyPreview(2L)).containsExactly(secondPreview)
        }

    @Test
    fun `toggle like updates preview reply only after success`() =
        runTest(dispatcher) {
            val root = comment(rpid = 1L, replyCount = 1)
            val preview = comment(rpid = 11L, rootRpid = 1L)
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(
                    listOf(root),
                    page = 1,
                    total = 1,
                    hasMore = false,
                    replyPreviews = mapOf(1L to listOf(preview)),
                )
            coEvery { repository.toggleCommentLike(any(), any(), any(), any()) } returns Unit

            viewModel.load(100L)
            advanceUntilIdle()

            viewModel.toggleLike(11L)
            // 请求完成前不乐观更新
            assertThat(
                viewModel.uiState.value
                    .findComment(11L)
                    ?.isLiked,
            ).isFalse()

            advanceUntilIdle()
            val updated =
                viewModel.uiState.value.commentList
                    .replyPreview(1L)
                    .single()
            assertThat(updated.isLiked).isTrue()
            assertThat(updated.likeCount).isEqualTo(1L)
            coVerify { repository.toggleCommentLike(100L, 11L, true, any()) }
        }

    @Test
    fun `load more replies appends next page`() =
        runTest(dispatcher) {
            val root = comment(rpid = 1L, replyCount = 25)
            val firstPage = (1L..20L).map { comment(rpid = 100L + it, rootRpid = 1L) }
            val secondPage = (21L..25L).map { comment(rpid = 100L + it, rootRpid = 1L) }
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(root), page = 1, total = 1, hasMore = false)
            coEvery { repository.getReplies(100L, 1L, 1, null, any()) } returns
                page(firstPage, page = 1, total = 25, hasMore = true)
            coEvery { repository.getReplies(100L, 1L, 2, null, any()) } returns
                page(secondPage, page = 2, total = 25, hasMore = false)

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.toggleReplies(1L)
            advanceUntilIdle()
            assertThat(
                viewModel.uiState.value.commentList
                    .replyList(1L)
                    ?.replies,
            ).hasSize(20)

            viewModel.loadMoreReplies(1L)
            advanceUntilIdle()

            val replyList =
                viewModel.uiState.value.commentList
                    .replyList(1L)
            assertThat(replyList?.replies).hasSize(25)
            assertThat(replyList?.hasMore).isFalse()
        }

    @Test
    fun `toggle like updates nested reply only after success`() =
        runTest(dispatcher) {
            val root = comment(rpid = 1L, replyCount = 1)
            val reply = comment(rpid = 2L, rootRpid = 1L)
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(root), page = 1, total = 1, hasMore = false)
            coEvery { repository.getReplies(any(), any(), any(), any(), any()) } returns
                page(listOf(reply), page = 1, total = 1, hasMore = false)
            coEvery { repository.toggleCommentLike(any(), any(), any(), any()) } returns Unit

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.toggleReplies(1L)
            advanceUntilIdle()

            viewModel.toggleLike(2L)
            assertThat(
                viewModel.uiState.value
                    .findComment(2L)
                    ?.isLiked,
            ).isFalse()

            advanceUntilIdle()
            val updated = viewModel.uiState.value.findComment(2L)
            assertThat(updated?.isLiked).isTrue()
            assertThat(updated?.likeCount).isEqualTo(1L)
            coVerify { repository.toggleCommentLike(100L, 2L, true, any()) }
        }

    @Test
    fun `toggle like emits toast and keeps state on failure`() =
        runTest(dispatcher) {
            val root = comment(rpid = 1L)
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(root), page = 1, total = 1, hasMore = false)
            coEvery { repository.toggleCommentLike(any(), any(), any(), any()) } throws
                RuntimeException("网络错误")

            viewModel.load(100L)
            advanceUntilIdle()

            viewModel.uiEffect.test {
                viewModel.toggleLike(1L)
                advanceUntilIdle()

                assertThat(
                    viewModel.uiState.value
                        .findComment(1L)
                        ?.isLiked,
                ).isFalse()
                val effect = awaitItem() as CommentUiEffect.ShowToast
                assertThat(effect.message).contains("评论点赞失败")
            }
        }

    @Test
    fun `submitReply posts main comment and refreshes comment list`() =
        runTest(dispatcher) {
            // Given：已登录且评论列表已加载
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(comment(rpid = 1L)), page = 1, total = 1, hasMore = false)
            coEvery { repository.addReply(any(), any(), any(), any(), any()) } returns Unit

            viewModel.load(100L)
            advanceUntilIdle()

            // When：发表主评论
            viewModel.openReplyInput(null)
            viewModel.updateReplyDraft("  前排支持  ")
            viewModel.submitReply()
            advanceUntilIdle()

            // Then：按 trim 后内容不带 root/parent 发送，弹窗关闭并刷新首页评论
            coVerify(exactly = 1) {
                repository.addReply(100L, "前排支持", null, null, any())
            }
            assertThat(viewModel.uiState.value.replyInput).isNull()
            coVerify(exactly = 2) { repository.getComments(100L, 1, 1, null, any()) }
        }

    @Test
    fun `submitReply posts nested reply and reloads that reply list`() =
        runTest(dispatcher) {
            // Given：主评论与楼中楼均已加载
            val root = comment(rpid = 1L, replyCount = 1)
            val reply = comment(rpid = 2L, rootRpid = 1L)
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(root), page = 1, total = 1, hasMore = false)
            coEvery { repository.getReplies(any(), any(), any(), any(), any()) } returns
                page(listOf(reply), page = 1, total = 1, hasMore = false)
            coEvery { repository.addReply(any(), any(), any(), any(), any()) } returns Unit

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.toggleReplies(1L)
            advanceUntilIdle()

            // When：回复根评论所在的楼中楼
            viewModel.openReplyInput(ReplyTarget(rootRpid = 1L, parentRpid = 1L))
            viewModel.updateReplyDraft("支持")
            viewModel.submitReply()
            advanceUntilIdle()

            // Then：带 root/parent 发送，该楼中楼第一页被重载且保持展开，弹窗关闭
            coVerify(exactly = 1) { repository.addReply(100L, "支持", 1L, 1L, any()) }
            assertThat(viewModel.uiState.value.replyInput).isNull()
            val replyList =
                viewModel.uiState.value.commentList
                    .replyList(1L)
            assertThat(replyList?.expanded).isTrue()
            assertThat(replyList?.replies).containsExactly(reply)
            coVerify(exactly = 2) { repository.getReplies(100L, 1L, 1, null, any()) }
        }

    @Test
    fun `submitReply failure keeps draft and shows friendly error`() =
        runTest(dispatcher) {
            // Given：已登录、弹窗已打开，发送被 B 站风控拦截
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(comment(rpid = 1L)), page = 1, total = 1, hasMore = false)
            coEvery { repository.addReply(any(), any(), any(), any(), any()) } throws
                RuntimeException("请求被拦截")

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.openReplyInput(null)
            viewModel.updateReplyDraft("测试")

            // When：提交失败
            viewModel.submitReply()
            advanceUntilIdle()

            // Then：输入保留、弹窗未关闭、错误提示转译为友好文案且列表不刷新
            val input = viewModel.uiState.value.replyInput
            assertThat(input).isNotNull()
            assertThat(input?.draft).isEqualTo("测试")
            assertThat(input?.submitting).isFalse()
            assertThat(input?.error).contains("稍后再试")
            coVerify(exactly = 1) { repository.addReply(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { repository.getComments(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `submitReply ignores duplicate submissions while in flight`() =
        runTest(dispatcher) {
            // Given：发送请求被挂起
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(comment(rpid = 1L)), page = 1, total = 1, hasMore = false)
            coEvery { repository.addReply(any(), any(), any(), any(), any()) } coAnswers { gate.await() }

            viewModel.load(100L)
            advanceUntilIdle()
            viewModel.openReplyInput(null)
            viewModel.updateReplyDraft("防抖")

            // When：发送中重复提交
            viewModel.submitReply()
            runCurrent()
            viewModel.submitReply()
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            // Then：只发送一次且最终成功关闭弹窗
            coVerify(exactly = 1) { repository.addReply(any(), any(), any(), any(), any()) }
            assertThat(viewModel.uiState.value.replyInput).isNull()
        }

    @Test
    fun `openReplyInput without login shows guide and blocks submit`() =
        runTest(dispatcher) {
            // Given：未登录
            every { accountRepository.isLogin() } returns false
            coEvery { repository.getComments(any(), any(), any(), any(), any()) } returns
                page(listOf(comment(rpid = 1L)), page = 1, total = 1, hasMore = false)

            viewModel.load(100L)
            advanceUntilIdle()

            // When：打开回复弹窗并输入
            viewModel.openReplyInput(null)
            viewModel.updateReplyDraft("未登录也要发")
            val input = viewModel.uiState.value.replyInput

            // Then：直接进入引导态，提交被拦截，草稿不会被发送
            assertThat(input?.notLoggedIn).isTrue()
            assertThat(input?.error).isEqualTo(REPLY_LOGIN_PROMPT)
            assertThat(input?.draft).isEqualTo("未登录也要发")

            viewModel.submitReply()
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.addReply(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `updateReplyDraft truncates input beyond limit`() =
        runTest(dispatcher) {
            // Given：弹窗已打开
            viewModel.openReplyInput(null)

            // When：输入超出 1000 字上限
            viewModel.updateReplyDraft("啊".repeat(MAX_REPLY_LENGTH + 50))

            // Then：截断到上限
            assertThat(
                viewModel.uiState.value.replyInput
                    ?.draft,
            ).hasLength(MAX_REPLY_LENGTH)
        }

    private fun page(
        comments: List<Comment>,
        page: Int,
        total: Int = comments.size,
        hasMore: Boolean,
        replyPreviews: Map<Long, List<Comment>> = emptyMap(),
    ) = CommentPage(
        comments = comments,
        page = page,
        total = total,
        hasMore = hasMore,
        replyPreviews = replyPreviews,
    )

    private fun comment(
        rpid: Long,
        rootRpid: Long = 0L,
        replyCount: Int = 0,
    ) = Comment(
        rpid = rpid,
        oid = 100L,
        type = 1,
        mid = 10L,
        rootRpid = rootRpid,
        parentRpid = 0L,
        userName = "测试用户",
        avatar = "",
        level = 6,
        message = "测试评论",
        pictures = emptyList(),
        ctime = 0L,
        likeCount = 0L,
        replyCount = replyCount,
        isLiked = false,
        isUp = false,
    )
}

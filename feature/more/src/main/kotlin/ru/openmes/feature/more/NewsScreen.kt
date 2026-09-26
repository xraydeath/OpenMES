package ru.openmes.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.common.toHM
import ru.openmes.core.common.toRuDate
import ru.openmes.core.data.CollegeRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.model.NewsBlock
import ru.openmes.core.model.NewsItem
import java.time.LocalDate
import java.time.LocalDateTime

class NewsViewModel(
    sessionRepository: SessionRepository,
    private val collegeRepository: CollegeRepository,
) : ViewModel() {

    data class NewsUiState(
        val items: List<NewsItem> = emptyList(),
        val page: Int = 0,
        val pageCount: Int = 1,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
    ) {
        val canLoadMore get() = page in 1 until pageCount
    }

    private val _state = MutableStateFlow(NewsUiState())
    val state = _state.asStateFlow()

    init {
        sessionRepository.session
            .onEach { if (it is Session.LoggedIn && _state.value.page == 0) refresh(force = false) }
            .launchIn(viewModelScope)
    }

    /** Подгрузка следующей страницы: обновление её отменяет, чтобы старые страницы не легли поверх новой первой. */
    private var moreJob: Job? = null
    private var refreshJob: Job? = null

    fun refresh(force: Boolean = true) {
        moreJob?.cancel()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, loadingMore = false, error = null)
            runSuspendCatching { collegeRepository.getNews(1, force) }
                .onSuccess {
                    _state.value = NewsUiState(items = it.items, page = it.page, pageCount = it.pageCount, loading = false)
                }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message) }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.canLoadMore) return
        moreJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            runSuspendCatching { collegeRepository.getNews(s.page + 1) }
                .onSuccess { page ->
                    val current = _state.value
                    val known = current.items.mapTo(HashSet()) { it.id }
                    _state.value = current.copy(
                        items = current.items + page.items.filter { it.id !in known },
                        page = page.page,
                        pageCount = page.pageCount,
                        loadingMore = false,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loadingMore = false) }
        }
    }
}

class NewsDetailViewModel(
    private val id: Long,
    private val collegeRepository: CollegeRepository,
) : ViewModel() {

    data class NewsDetailUiState(
        val item: NewsItem? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(NewsDetailUiState())
    val state = _state.asStateFlow()

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runSuspendCatching { collegeRepository.getNewsItem(id, force) }
                .onSuccess { _state.value = NewsDetailUiState(item = it, loading = false) }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message) }
        }
    }
}

/** Лента новостей (api/news/v2): обложка, заголовок, канал и дата; подгрузка страниц по прокрутке. */
@Composable
fun NewsScreen(viewModel: NewsViewModel, onOpenNews: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.items.size) {
        if (nearEnd && state.items.isNotEmpty()) viewModel.loadMore()
    }

    MesPullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading && state.items.isEmpty() -> LoadingState()
            state.error != null && state.items.isEmpty() -> ScrollableFill {
                ErrorState(
                    title = "Не удалось загрузить новости",
                    onRetry = viewModel::refresh,
                    details = state.error,
                )
            }

            state.items.isEmpty() -> ScrollableFill {
                EmptyState(icon = Icons.Rounded.Newspaper, title = "Новостей пока нет")
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    NewsCard(item, onClick = { onOpenNews(item.id) })
                }
                if (state.loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(40.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem, onClick: () -> Unit) {
    MesCard(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        item.coverUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
        }
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NewsMeta(item)
            Text(
                item.title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Канал (с логотипом) и дата публикации. */
@Composable
private fun NewsMeta(item: NewsItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.channelLogo?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.size(20.dp).clip(CircleShape),
            )
        }
        Text(
            listOfNotNull(item.channel, item.publishedAt?.toNewsDate()).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Новость целиком: текстовые блоки из HTML, изображения, ссылки на видео. */
@Composable
fun NewsDetailScreen(viewModel: NewsDetailViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = remember(linkColor) {
        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    }

    MesPullToRefreshBox(
        isRefreshing = state.loading && item != null,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading && item == null -> LoadingState()
            item == null -> ScrollableFill {
                ErrorState(title = "Не удалось открыть новость", onRetry = viewModel::refresh, details = state.error)
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item.coverUrl?.let { cover ->
                    item {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.largeIncreased),
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NewsMeta(item)
                        Text(item.title, style = MaterialTheme.typography.headlineSmallEmphasized)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.tags.forEach { StatusPill(it) }
                            item.views?.let { StatusPill("$it", icon = Icons.Rounded.Visibility) }
                        }
                    }
                }
                items(item.blocks) { block ->
                    when (block) {
                        is NewsBlock.Text -> Text(
                            remember(block.html, linkStyles) { AnnotatedString.fromHtml(block.html, linkStyles).trimBlank() },
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        is NewsBlock.Image -> AsyncImage(
                            model = block.url,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
                        )

                        is NewsBlock.Video -> MesListItem(
                            headline = "Видео",
                            supporting = "Открыть в браузере",
                            icon = Icons.Rounded.PlayCircle,
                            onClick = { context.openUrl(block.url) },
                        )
                    }
                }
            }
        }
    }
}

/** Без пустых абзацев в конце (редактор новостей добавляет `<p><br></p>`). */
private fun AnnotatedString.trimBlank(): AnnotatedString {
    val end = indexOfLast { !it.isWhitespace() } + 1
    val start = indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    return if (end <= start) AnnotatedString("") else subSequence(start, end)
}

/** «12 сентября, 14:30» в текущем году, иначе «12 сентября 2025». */
private fun LocalDateTime.toNewsDate(): String =
    if (year == LocalDate.now().year) "${toLocalDate().toRuDate()}, ${toLocalTime().toHM()}"
    else toLocalDate().toRuDate(includeYear = true)

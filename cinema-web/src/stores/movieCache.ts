/**
 * 路由级 Pinia 缓存 (Phase D-⑯)
 * <ul>
 *   <li>5 分钟 TTL 兜底, 减少重复请求</li>
 *   <li>首屏 Home/MovieDetail 仍走 API, 后续路由进入命中缓存</li>
 *   <li>Admin CRUD 不会主动失效缓存, 5 分钟内用户可能看到陈旧数据 — 与方案文档 §5 一致</li>
 * </ul>
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Movie, SessionVO, PageData } from '../types'

const TTL_MS = 5 * 60 * 1000

type Entry<T> = { value: T; expireAt: number }

export const useMovieCache = defineStore('movieCache', () => {
  const movies = ref<Entry<Movie[]> | null>(null)
  const movieDetails = ref<Map<number, Entry<Movie>>>(new Map())
  const sessionLists = ref<Map<string, Entry<SessionVO[]>>>(new Map())
  const moviePages = ref<Map<string, Entry<PageData<Movie>>>>(new Map())

  const now = () => Date.now()
  const alive = <T,>(e: Entry<T> | null | undefined) => !!e && e.expireAt > now()

  function setMovies(list: Movie[]) {
    movies.value = { value: list, expireAt: now() + TTL_MS }
  }
  function getMovies(): Movie[] | null {
    return alive(movies.value) ? movies.value!.value : null
  }

  function setMovieDetail(id: number, m: Movie) {
    movieDetails.value.set(id, { value: m, expireAt: now() + TTL_MS })
  }
  function getMovieDetail(id: number): Movie | null {
    const e = movieDetails.value.get(id)
    return alive(e) ? e!.value : null
  }

  function sessionListKey(movieId: number, date: string) {
    return `${movieId}:${date}`
  }
  function setSessionList(movieId: number, date: string, list: SessionVO[]) {
    sessionLists.value.set(sessionListKey(movieId, date), {
      value: list,
      expireAt: now() + TTL_MS,
    })
  }
  function getSessionList(movieId: number, date: string): SessionVO[] | null {
    const e = sessionLists.value.get(sessionListKey(movieId, date))
    return alive(e) ? e!.value : null
  }

  function setMoviePage(key: string, page: PageData<Movie>) {
    moviePages.value.set(key, { value: page, expireAt: now() + TTL_MS })
  }
  function getMoviePage(key: string): PageData<Movie> | null {
    const e = moviePages.value.get(key)
    return alive(e) ? e!.value : null
  }

  function invalidate() {
    movies.value = null
    movieDetails.value.clear()
    sessionLists.value.clear()
    moviePages.value.clear()
  }

  return {
    setMovies, getMovies,
    setMovieDetail, getMovieDetail,
    setSessionList, getSessionList,
    setMoviePage, getMoviePage,
    invalidate,
  }
})

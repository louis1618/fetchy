package com.luverse.fetchy

import java.io.IOException

internal enum class FailureKind(val code: String, val message: String, val help: String, val retryable: Boolean = false) {
    CANCELED("canceled", "작업을 중단했습니다.", "앱에서 다시 시작할 수 있습니다."),
    SPACE("storage_space", "저장 공간이 부족합니다.", "기기 공간을 확보하거나 저장 위치를 변경하세요."),
    STORAGE("storage_permission", "저장 위치에 접근할 수 없습니다.", "설정에서 저장 위치를 다시 승인하세요."),
    BUSY("operation_busy", "진행 중인 파일 작업이 있습니다.", "다운로드를 일시정지하거나 저장 위치 이전이 끝난 뒤 다시 시도하세요."),
    CONFLICT("name_conflict", "같은 이름의 파일이나 폴더가 있습니다.", "다른 이름을 사용하세요."),
    NETWORK("network", "네트워크 연결이 원활하지 않습니다.", "연결 상태를 확인하고 다시 시도하세요.", true),
    LIMIT("rate_limit", "플랫폼이 요청을 일시적으로 제한했습니다.", "잠시 기다렸다가 다시 시도하세요.", true),
    MEMBERSHIP("membership", "이 콘텐츠의 이용 권한이 필요합니다.", "해당 멤버십·구매·초대 권한이 있는 계정인지 확인하세요."),
    AUTH("login_required", "이 콘텐츠에는 로그인이 필요합니다.", "설정에서 로그인 상태를 확인하거나 쿠키를 다시 저장하세요."),
    AGE("age_restricted", "계정의 연령 확인이 필요한 콘텐츠입니다.", "원본 서비스에서 연령 확인 후 해당 계정의 쿠키를 저장하세요."),
    MISSING("media_missing", "원본 파일이 삭제됐거나 제공되지 않습니다.", "원본 링크를 열어 확인하세요. 스토리는 만료됐을 수 있습니다."),
    FORMAT("format_unavailable", "선택한 원본 형식을 제공하지 않습니다.", "다른 화질·형식 또는 최고 화질을 선택하세요."),
    DRM("drm", "보호된 미디어 형식은 저장할 수 없습니다.", "원본 서비스에서 재생하세요."),
    UNSUPPORTED("unsupported", "지원되지 않는 링크입니다.", "개별 게시물·영상의 공유 링크를 사용하세요."),
    ENGINE("engine", "미디어 처리 엔진에서 오류가 발생했습니다.", "앱을 업데이트하거나 문제 진단을 공유하세요."),
    OTHER("operation", "요청을 완료하지 못했습니다.", "원본 접근 권한과 저장 위치를 확인하고 다시 시도하세요.");

    companion object {
        fun from(error: Throwable): FailureKind {
            if (error is DownloadFailure) return error.kind
            val text = generateSequence(error) { it.cause }.take(4).joinToString(" ") { it.message.orEmpty() }.lowercase()
            fun has(pattern: String) = Regex(pattern).containsMatchIn(text)
            return when {
                has("cancel|canceled|interrupted|중단") -> CANCELED
                has("no space|enospc|disk full|공간이 부족") -> SPACE
                error is SecurityException || has("permission denied|eacces|persistable|provider unavailable|cannot enumerate|destination unavailable|저장 위치에 접근") -> STORAGE
                has("migration is pending|active job|pause downloads|pending file operations|진행 중인 파일 작업") -> BUSY
                has("name already exists|same name|같은 이름") -> CONFLICT
                has("429|too many requests|rate.limit|요청을 일시") -> LIMIT
                has("timed? ?out|timeout|connection|network|temporary|http error 5[0-9]{2}|네트워크") -> NETWORK
                has("members.only|membership|premium.only|subscriber.only|join this channel|purchase|이용 권한") -> MEMBERSHIP
                has("age.restrict|confirm your age|verify your age|연령 확인") -> AGE
                has("login required|log in|sign in|authentication|private (video|account)|protected|not a bot|로그인이 필요") -> AUTH
                has("drm|encrypted media|보호된 미디어") -> DRM
                has("unsupported (url|link)|지원되지 않는 링크") -> UNSUPPORTED
                has("requested format|no (video formats|supported original media)|selected (format|asset).*no longer|원본 형식을") -> FORMAT
                has("not found|unavailable|deleted|http error (404|410)|does not exist|document missing|원본 파일이 삭제") -> MISSING
                has("lfh|zip|ffmpeg|ffprobe|quickjs|python|validation failed|decode validation|conversion failed|metadata write failed|engine|signature|처리 엔진") -> ENGINE
                else -> OTHER
            }
        }
        fun byCode(code: String) = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

internal class DownloadFailure(val kind: FailureKind) : IOException(kind.message)


package io.github.sejoung.panelyink.core.sort

/**
 * "page_2.jpg" < "page_10.jpg" 같은 사람-친화 정렬.
 *
 * macOS Panely의 자연 정렬 정책 포팅. CBZ 페이지 목록과 라이브러리 책 이름에
 * 동일 비교자를 사용해 일관성을 유지한다.
 *
 * 알고리즘: 두 문자열을 동시에 훑으며 숫자 청크는 정수로 비교, 나머지는
 * 대소문자 무시 코드포인트 비교. 길이가 같지 않으면 짧은 쪽이 앞.
 */
object NaturalOrderComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val (av, ai) = readNumber(a, i)
                val (bv, bj) = readNumber(b, j)
                val cmp = av.compareTo(bv)
                if (cmp != 0) return cmp
                // 숫자 값이 같지만 0 패딩 길이가 다른 경우(예: "01" vs "1")
                // 짧은 쪽을 앞으로 — 자연스러운 디렉터리 정렬과 일치
                val padCmp = (ai - i).compareTo(bj - j)
                if (padCmp != 0) return padCmp
                i = ai
                j = bj
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun readNumber(s: String, from: Int): Pair<Long, Int> {
        var i = from
        var v = 0L
        while (i < s.length && s[i].isDigit()) {
            v = v * 10 + (s[i] - '0')
            i++
        }
        return v to i
    }
}

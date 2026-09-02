package com.eareyereading.domain.model

/**
 * 可一键下载的英文经典名著（长篇，供离线阅读）。
 * 数据源：Project Gutenberg 公版书全文（cache/epub 稳定镜像的纯文本）。
 */
data class ClassicBook(
    val id: String,
    val title: String,
    val author: String,
    val url: String,          // Gutenberg 纯文本直链（.txt）
    val gutenbergId: Int,     // 用于生成确定性的本地文件名（去重）
    val difficulty: Int = 3,  // 1-5 难度
    val description: String? = null,
)

/** 预置英文经典名著清单（全部为公版书、Gutenberg 已验证可用） */
object ClassicBooks {
    val list = listOf(
        ClassicBook(
            id = "pride_and_prejudice", title = "Pride and Prejudice", author = "Jane Austen",
            url = "https://www.gutenberg.org/cache/epub/1342/pg1342.txt", gutenbergId = 1342,
            difficulty = 3, description = "傲慢与偏见，简·奥斯汀代表作，英国经典爱情小说",
        ),
        ClassicBook(
            id = "jane_eyre", title = "Jane Eyre", author = "Charlotte Brontë",
            url = "https://www.gutenberg.org/cache/epub/1260/pg1260.txt", gutenbergId = 1260,
            difficulty = 4, description = "简·爱，夏洛蒂·勃朗特长篇自传体小说",
        ),
        ClassicBook(
            id = "wuthering_heights", title = "Wuthering Heights", author = "Emily Brontë",
            url = "https://www.gutenberg.org/cache/epub/768/pg768.txt", gutenbergId = 768,
            difficulty = 4, description = "呼啸山庄，艾米莉·勃朗特的哥特式爱情经典",
        ),
        ClassicBook(
            id = "great_expectations", title = "Great Expectations", author = "Charles Dickens",
            url = "https://www.gutenberg.org/cache/epub/1400/pg1400.txt", gutenbergId = 1400,
            difficulty = 4, description = "远大前程，查尔斯·狄更斯的成长小说名著",
        ),
        ClassicBook(
            id = "tale_of_two_cities", title = "A Tale of Two Cities", author = "Charles Dickens",
            url = "https://www.gutenberg.org/cache/epub/98/pg98.txt", gutenbergId = 98,
            difficulty = 4, description = "双城记，以法国大革命为背景的传世之作",
        ),
        ClassicBook(
            id = "moby_dick", title = "Moby Dick", author = "Herman Melville",
            url = "https://www.gutenberg.org/cache/epub/2701/pg2701.txt", gutenbergId = 2701,
            difficulty = 5, description = "白鲸，赫尔曼·梅尔维尔的海洋文学巨著",
        ),
        ClassicBook(
            id = "frankenstein", title = "Frankenstein", author = "Mary Shelley",
            url = "https://www.gutenberg.org/cache/epub/84/pg84.txt", gutenbergId = 84,
            difficulty = 3, description = "弗兰肯斯坦，玛丽·雪莱的科幻小说开山之作",
        ),
        ClassicBook(
            id = "dracula", title = "Dracula", author = "Bram Stoker",
            url = "https://www.gutenberg.org/cache/epub/345/pg345.txt", gutenbergId = 345,
            difficulty = 4, description = "德古拉，布拉姆·斯托克的吸血鬼惊悚经典",
        ),
        ClassicBook(
            id = "treasure_island", title = "Treasure Island", author = "Robert Louis Stevenson",
            url = "https://www.gutenberg.org/cache/epub/120/pg120.txt", gutenbergId = 120,
            difficulty = 3, description = "金银岛，罗伯特·史蒂文森的冒险小说经典",
        ),
        ClassicBook(
            id = "tom_sawyer", title = "The Adventures of Tom Sawyer", author = "Mark Twain",
            url = "https://www.gutenberg.org/cache/epub/74/pg74.txt", gutenbergId = 74,
            difficulty = 3, description = "汤姆·索亚历险记，马克·吐温的童年历险",
        ),
        ClassicBook(
            id = "little_women", title = "Little Women", author = "Louisa May Alcott",
            url = "https://www.gutenberg.org/cache/epub/514/pg514.txt", gutenbergId = 514,
            difficulty = 3, description = "小妇人，路易莎·奥尔科特的成长经典",
        ),
        ClassicBook(
            id = "dorian_gray", title = "The Picture of Dorian Gray", author = "Oscar Wilde",
            url = "https://www.gutenberg.org/cache/epub/174/pg174.txt", gutenbergId = 174,
            difficulty = 4, description = "道林·格雷的画像，王尔德唯一的长篇小说",
        ),
        ClassicBook(
            id = "alice_wonderland", title = "Alice's Adventures in Wonderland", author = "Lewis Carroll",
            url = "https://www.gutenberg.org/cache/epub/11/pg11.txt", gutenbergId = 11,
            difficulty = 2, description = "爱丽丝梦游仙境，路易斯·卡罗尔的奇幻童话",
        ),
        ClassicBook(
            id = "call_of_the_wild", title = "The Call of the Wild", author = "Jack London",
            url = "https://www.gutenberg.org/cache/epub/215/pg215.txt", gutenbergId = 215,
            difficulty = 3, description = "野性的呼唤，杰克·伦敦的动物冒险小说",
        ),
        ClassicBook(
            id = "robinson_crusoe", title = "Robinson Crusoe", author = "Daniel Defoe",
            url = "https://www.gutenberg.org/cache/epub/521/pg521.txt", gutenbergId = 521,
            difficulty = 3, description = "鲁滨逊漂流记，笛福的荒岛求生经典",
        ),
    )

    fun getById(id: String) = list.find { it.id == id }
}
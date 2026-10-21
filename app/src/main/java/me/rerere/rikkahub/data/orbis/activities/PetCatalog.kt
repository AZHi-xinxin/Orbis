package me.rerere.rikkahub.data.orbis.activities

data class PetAppearance(val id: String, val family: String, val label: String)
object PetCatalog {
    val appearances = listOf(
        PetAppearance("cat-tabby-v1", "cat", "狸花加白"),
        PetAppearance("cat-orange-v1", "cat", "橘猫"),
        PetAppearance("cat-tuxedo-v1", "cat", "黑白燕尾服"),
        PetAppearance("cat-black-v1", "cat", "纯黑猫"),
        PetAppearance("cat-silver-v1", "cat", "银白长毛猫"),
        PetAppearance("cat-blue-white-v1", "cat", "蓝白短毛猫"),
        PetAppearance("cat-calico-v1", "cat", "三花猫"),
        PetAppearance("cat-point-v1", "cat", "重点色猫"),
        PetAppearance("cat-cream-v1", "cat", "奶油长毛猫"),
        PetAppearance("cat-brown-longhair-v1", "cat", "棕虎斑长毛猫"),
        PetAppearance("dog-shiba-v1", "dog", "柴犬"),
        PetAppearance("dog-corgi-v1", "dog", "柯基"),
        PetAppearance("dog-golden-v1", "dog", "金毛"),
        PetAppearance("dog-labrador-v1", "dog", "拉布拉多"),
        PetAppearance("dog-collie-v1", "dog", "边牧"),
        PetAppearance("dog-beagle-v1", "dog", "比格"),
        PetAppearance("dog-poodle-v1", "dog", "贵宾"),
        PetAppearance("dog-pomeranian-v1", "dog", "博美"),
        PetAppearance("dog-samoyed-v1", "dog", "萨摩耶"),
        PetAppearance("dog-dachshund-v1", "dog", "腊肠"),
        PetAppearance("bird-budgie-v1", "bird", "虎皮鹦鹉"),
        PetAppearance("bird-cockatiel-v1", "bird", "玄凤鹦鹉"),
        PetAppearance("bird-lovebird-v1", "bird", "牡丹鹦鹉"),
        PetAppearance("bird-conure-v1", "bird", "小太阳鹦鹉"),
        PetAppearance("bird-java-v1", "bird", "文鸟"),
        PetAppearance("bird-zebra-v1", "bird", "斑胸草雀"),
        PetAppearance("bird-canary-v1", "bird", "金丝雀"),
        PetAppearance("bird-pigeon-v1", "bird", "鸽子"),
        PetAppearance("bird-lineolated-v1", "bird", "横斑鹦鹉"),
        PetAppearance("bird-monk-v1", "bird", "和尚鹦鹉"),
        PetAppearance("reptile-leopard-gecko-v1", "reptile", "豹纹守宫"),
        PetAppearance("reptile-crested-gecko-v1", "reptile", "睫角守宫"),
        PetAppearance("reptile-bearded-dragon-v1", "reptile", "鬃狮蜥"),
        PetAppearance("reptile-blue-tongue-v1", "reptile", "蓝舌石龙子"),
        PetAppearance("reptile-corn-snake-v1", "reptile", "玉米蛇"),
        PetAppearance("reptile-ball-python-v1", "reptile", "球蟒"),
        PetAppearance("reptile-hognose-v1", "reptile", "猪鼻蛇"),
        PetAppearance("reptile-hermann-v1", "reptile", "赫曼陆龟"),
        PetAppearance("reptile-redfoot-v1", "reptile", "红腿陆龟"),
        PetAppearance("reptile-musk-v1", "reptile", "麝香龟")
    )
    fun find(id: String): PetAppearance = appearances.singleOrNull { it.id == id } ?: error("unknown_pet_appearance")
}


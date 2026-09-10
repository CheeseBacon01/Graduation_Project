package com.example.graduationproject.fitness

/** 五邊形圖的五個訓練指標 */
enum class FitnessCategory(val label: String, val apiKey: String) {
    CARDIO("心肺功能", "CARDIO"),
    UPPER_STRENGTH("上肢肌耐力", "UPPER_STRENGTH"),
    LOWER_STRENGTH("下肢肌耐力", "LOWER_STRENGTH"),
    BALANCE("平衡感", "BALANCE"),
    FLEXIBILITY("靈活度", "FLEXIBILITY")
}
object ExerciseCategoryMap {
    private val map: Map<String, FitnessCategory> = mapOf(
        // 心肺功能：步行
        "A-1" to FitnessCategory.CARDIO,
        "B-1" to FitnessCategory.CARDIO,
        "C-1" to FitnessCategory.CARDIO,
        "D-1" to FitnessCategory.CARDIO,

        // 上肢肌耐力：擠壓球、舉水瓶、扭毛巾
        "A-2" to FitnessCategory.UPPER_STRENGTH,
        "A-3" to FitnessCategory.UPPER_STRENGTH,
        "B-2" to FitnessCategory.UPPER_STRENGTH,
        "B-3" to FitnessCategory.UPPER_STRENGTH,
        "C-2" to FitnessCategory.UPPER_STRENGTH,
        "C-3" to FitnessCategory.UPPER_STRENGTH,
        "D-2" to FitnessCategory.UPPER_STRENGTH,
        "D-3" to FitnessCategory.UPPER_STRENGTH,

        // 下肢肌耐力：腳踝負重腿部訓練、椅子起身、模擬坐下、上下樓梯
        "A-4" to FitnessCategory.LOWER_STRENGTH,
        "A-5" to FitnessCategory.LOWER_STRENGTH,
        "B-4" to FitnessCategory.LOWER_STRENGTH,
        "C-4" to FitnessCategory.LOWER_STRENGTH,
        "D-4" to FitnessCategory.LOWER_STRENGTH,
        "D-5" to FitnessCategory.LOWER_STRENGTH,

        // 平衡感：直線走路、腳尖腳跟走路、跨越障礙物、8字步、邊拍氣球走路
        "A-6" to FitnessCategory.BALANCE,
        "B-5" to FitnessCategory.BALANCE,
        "C-5" to FitnessCategory.BALANCE,
        "C-6" to FitnessCategory.BALANCE,
        "D-6" to FitnessCategory.BALANCE,
        "D-7" to FitnessCategory.BALANCE,

        // 靈活度：手臂伸展、椅上伸展手臂、腿部伸展
        "A-7" to FitnessCategory.FLEXIBILITY,
        "B-6" to FitnessCategory.FLEXIBILITY,
        "B-7" to FitnessCategory.FLEXIBILITY,
        "C-7" to FitnessCategory.FLEXIBILITY,
        "C-8" to FitnessCategory.FLEXIBILITY,
        "D-8" to FitnessCategory.FLEXIBILITY,
        "D-9" to FitnessCategory.FLEXIBILITY
    )

    fun categoryOf(exerciseCode: String): FitnessCategory? = map[exerciseCode]
}
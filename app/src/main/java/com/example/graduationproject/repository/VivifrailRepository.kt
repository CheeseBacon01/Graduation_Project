package com.example.graduationproject.repository

import com.example.graduationproject.DataClass.DailyPlan
import com.example.graduationproject.DataClass.Exercise
import com.example.graduationproject.DataClass.ExerciseStatus

class VivifrailRepository {
    fun getDailyPlan(level: String, day: Int): DailyPlan {
        // 關鍵修正：將 "B+" 或 "C+" 轉為 "B" 或 "C" 以匹配資料庫
        val baseLevel = level.take(1).uppercase()
        
        val exercises = when (baseLevel) {
            "A" -> listOf(
                Exercise("A1", "步行", "輕鬆", "5次以上", "5-10秒/次", "視需要休息"),
                Exercise("A2", "擠壓球", "輕鬆", "3組", "12次", "休息1分鐘"),
                Exercise("A3", "舉水瓶", "中等", "3組", "12次", "休息1分鐘"),
                Exercise("A4", "腳踝負重腿部伸展", "中等", "3組", "12次", "休息1分鐘"),
                Exercise("A5", "在人員輔助下從椅子起身", "中等", "3組", "12次", "休息1分鐘"),
                Exercise("A6", "直線走路", "中等", "3組", "15步", "休息30秒"),
                Exercise("A7", "往上伸展手臂", "輕鬆", "3組", "3次(維持10-12秒)", "休息30秒")
            )
            "B" -> if (day % 2 != 0) { // Day 1, 3, 5
                listOf(
                    Exercise("B1", "舉水瓶", "中等", "3組", "12次", "休息1分鐘"),
                    Exercise("B2", "擠壓球", "輕鬆", "3組", "12次", "休息1分鐘"),
                    Exercise("B3", "模擬坐下動作", "中等", "3組", "12次", "休息1分鐘"),
                    Exercise("B4", "用腳尖、腳跟走路", "中等", "3組", "14步", "休息1分鐘"),
                    Exercise("B5", "椅上伸展手臂", "輕鬆", "3組", "3次(維持10秒)", "休息30秒"),
                    Exercise("B6", "往上伸展手臂", "輕鬆", "3組", "3次(維持10-12秒)", "休息30秒")
                )
            } else { // Day 2, 4
                listOf(
                    Exercise("B7", "步行", "輕鬆", "5組", "2-5分鐘/組", "休息1分鐘")
                )
            }
            "C" -> if (day % 2 != 0) {
                listOf(
                    Exercise("C1", "扭毛巾", "中等", "3組", "12次(持續2-3秒)", "休息1分鐘"),
                    Exercise("C2", "舉水瓶", "中等", "3組", "12次", "休息1分鐘"),
                    Exercise("C3", "從椅子起身", "挑戰", "3組", "12次", "休息1分鐘"),
                    Exercise("C4", "跨越障礙物", "挑戰", "8組", "5次跨越", "休息1分鐘"),
                    Exercise("C5", "走 8 字步", "中等", "3組", "2圈", "休息1分鐘"),
                    Exercise("C6", "腿部伸展", "輕鬆", "3組", "每腿6次(維持10-12秒)", "休息1分鐘"),
                    Exercise("C7", "往上伸展手臂", "輕鬆", "3組", "3次(維持10-12秒)", "休息1分鐘")
                )
            } else {
                listOf(
                    Exercise("C8", "步行", "中等", "3組", "每組 10 分鐘", "休息1分鐘")
                )
            }
            "D" -> if (day % 2 != 0) {
                listOf(
                    Exercise("D1", "扭毛巾", "中等", "3組", "12次(持續2-3秒)", "休息1分鐘"),
                    Exercise("D2", "舉水瓶", "中等", "3組", "12次", "休息1分鐘"),
                    Exercise("D3", "從椅子起身", "挑戰", "3組", "12次", "休息1分鐘"),
                    Exercise("D4", "上下樓梯", "挑戰", "3組", "20步", "休息1分鐘"),
                    Exercise("D5", "邊拍氣球邊走路", "挑戰", "3組", "10步", "休息30秒"),
                    Exercise("D6", "走 8 字步", "挑戰", "3組", "2圈", "休息1分鐘"),
                    Exercise("D7", "往上伸展手臂", "輕鬆", "3組", "3次(維持10-12秒)", "休息30秒"),
                    Exercise("D8", "腿部伸展", "輕鬆", "3組", "每腿6次(維持10-12秒)", "休息30秒")
                )
            } else {
                listOf(
                    Exercise("D9", "步行", "挑戰", "2組", "每組 20 分鐘", "休息1分鐘")
                )
            }
            else -> emptyList()
        }
        return DailyPlan(day, level, exercises)
    }
}

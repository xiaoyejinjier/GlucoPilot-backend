#requires -Version 5.1
<#
  为一个用户生成"近一个月"的可供 AI 工具调用的模拟数据。

  覆盖 6 个工具里的 4 个数据源：
    glucose_record → query_glucose_range / get_daily_checkin / get_month_stats
    user_health    → get_health_profile（含 medication，用于验证"用药不返回给模型"）
    meal_record + meal_food_item → query_meal_records
    （food_nutrition 是全局参考数据，无需生成）

  设计要点：
    1) 确定性：固定随机种子（-Seed），同参数重复执行得到同一批数据；
    2) 可清理：所有行带 【模拟数据】 标记（glucose_record.remark / meal_record.supplement_text）；
    3) 幂等：先删该用户的旧模拟数据再插入，重复执行不累积；
    4) 贴近真实：六个测量点（三餐前后），餐后偏高、餐前偏低、少量低血糖与高值离群，
       约 10% 的日子整天不记，但"今天/昨天"一定有（否则打卡类工具看起来像停机），
       今天只记前 4 个点（让 get_daily_checkin 呈现"部分完成"）。
#>
param(
    [int[]]$UserIds = @(18),
    [int]$Days = 30,
    [int]$MealDays = 7,
    [int]$Seed = 20261012,
    [string]$Today = "2026-10-12"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_common.ps1"
$repo = (Get-E2eRepoRoot)
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$outDir = Get-E2eOutDir
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$cnf = Resolve-MysqlCnf

Get-Random -SetSeed $Seed | Out-Null   # 之后所有 Get-Random 调用都确定性

$endDate = [datetime]::ParseExact($Today, "yyyy-MM-dd", $null)
$nodes = @("before_breakfast", "after_breakfast", "before_lunch", "after_lunch", "before_dinner", "after_dinner")

$profile = @{
    before_breakfast = @{ lo = 5.2; hi = 7.0;  outLo = 7.1;  outHi = 8.2;  p = 0.08 }
    after_breakfast  = @{ lo = 7.0; hi = 10.2; outLo = 10.3; outHi = 13.5; p = 0.06 }
    before_lunch     = @{ lo = 5.2; hi = 6.8;  outLo = 3.4;  outHi = 3.9;  p = 0.03 }
    after_lunch      = @{ lo = 7.8; hi = 11.0; outLo = 11.1; outHi = 14.6; p = 0.06 }
    before_dinner    = @{ lo = 5.3; hi = 7.0;  outLo = 3.5;  outHi = 4.0;  p = 0.03 }
    after_dinner     = @{ lo = 8.0; hi = 11.5; outLo = 11.6; outHi = 14.8; p = 0.06 }
}

function New-Value([string]$node) {
    $p = $profile[$node]
    if ((Get-Random -Minimum 0.0 -Maximum 1.0) -lt $p.p) {
        $v = Get-Random -Minimum $p.outLo -Maximum $p.outHi
    } else {
        $v = Get-Random -Minimum $p.lo -Maximum $p.hi
    }
    $v = [math]::Round($v, 1)
    if ($v -le 0.1) { $v = 0.2 }
    if ($v -ge 39.9) { $v = 39.8 }
    return $v
}

# ---------- 读真实食物库，营养值不自己编 ----------
$foodRows = & $mysql "--defaults-extra-file=$cnf" "--database=sugar_health" "--batch" "--skip-column-names" `
    "--execute=SELECT id, food_name, carbs_per_100g, calories_per_100g FROM food_nutrition WHERE is_deleted=0;"
$foods = @{}
foreach ($r in $foodRows) {
    $c = $r -split "`t"
    $foods[$c[1]] = @{ id = [int]$c[0]; carbs = [double]$c[2]; kcal = [double]$c[3] }
}
$mealPlans = @{
    breakfast = @(@{ n = "燕麦"; w = 50 }, @{ n = "鸡蛋"; w = 60 })
    lunch     = @(@{ n = "米饭"; w = 200 }, @{ n = "鸡胸肉"; w = 100 }, @{ n = "西兰花"; w = 150 })
    dinner    = @(@{ n = "面条"; w = 150 }, @{ n = "瘦猪肉"; w = 80 }, @{ n = "黄瓜"; w = 100 })
}
$mealTimes = @{ breakfast = "08:00:00"; lunch = "12:30:00"; dinner = "19:00:00" }

$sql = New-Object System.Text.StringBuilder
[void]$sql.AppendLine("SET NAMES utf8mb4;")

foreach ($userId in $UserIds) {
    [void]$sql.AppendLine("-- ===== 用户 $userId：$($endDate.AddDays(-($Days-1)).ToString('yyyy-MM-dd')) ~ $Today（种子 $Seed）=====")
    [void]$sql.AppendLine("DELETE FROM meal_food_item WHERE meal_id IN (SELECT id FROM meal_record WHERE user_id=$userId AND supplement_text LIKE '【模拟数据】%');")
    [void]$sql.AppendLine("DELETE FROM meal_record WHERE user_id=$userId AND supplement_text LIKE '【模拟数据】%';")
    [void]$sql.AppendLine("DELETE FROM glucose_record WHERE user_id=$userId AND remark LIKE '【模拟数据】%';")

    # ---------- 血糖 ----------
    $values = New-Object System.Collections.ArrayList
    for ($d = $Days - 1; $d -ge 0; $d--) {
        $date = $endDate.AddDays(-$d)
        $isToday = ($d -eq 0)
        $isYesterday = ($d -eq 1)
        if (-not $isToday -and -not $isYesterday -and (Get-Random -Minimum 0.0 -Maximum 1.0) -lt 0.10) { continue }
        $dayNodes = if ($isToday) { $nodes[0..3] } else { $nodes }
        foreach ($node in $dayNodes) {
            $v = New-Value $node
            $src = if ((Get-Random -Minimum 0 -Maximum 3) -lt 2) { 1 } else { 3 }
            [void]$values.Add("($userId,'$($date.ToString('yyyy-MM-dd'))','$node',$v,$src,'【模拟数据】')")
        }
    }
    [void]$sql.AppendLine("INSERT INTO glucose_record (user_id, record_date, node_code, glucose_value, source_type, remark) VALUES")
    [void]$sql.AppendLine(($values -join ",`n") + ";")

    # ---------- 健康档案 ----------
    [void]$sql.AppendLine("UPDATE user_health SET age=52, diabetes_type=2, diabetes_type_text='2 型糖尿病', diagnosed_ym='2019-06', exercise_habit='每周快走 4 次，每次 40 分钟', medication='【模拟数据】二甲双胍缓释片 500mg，每日两次', target_fasting=5.5, target_postprandial=8.0 WHERE user_id=$userId;")

    # ---------- 饮食：每天三餐 + 明细（用 LAST_INSERT_ID 关联）----------
    for ($d = $MealDays - 1; $d -ge 0; $d--) {
        $date = $endDate.AddDays(-$d).ToString('yyyy-MM-dd')
        foreach ($meal in @("breakfast", "lunch", "dinner")) {
            $foodNames = New-Object System.Collections.ArrayList
            $itemSql = New-Object System.Collections.ArrayList
            $carbsSum = 0.0
            $sort = 1
            foreach ($entry in $mealPlans[$meal]) {
                $name = $entry.n; $weight = [double]$entry.w
                if (-not $foods.ContainsKey($name)) { continue }
                $f = $foods[$name]
                $carbs = [math]::Round($weight * $f.carbs / 100.0, 1)
                $kcal = [math]::Round($weight * $f.kcal / 100.0, 1)
                $carbsSum += $carbs
                [void]$foodNames.Add($name)
                [void]$itemSql.Add("(@mid,$($f.id),'$name',$weight,$carbs,$kcal,$sort)")
                $sort++
            }
            if ($itemSql.Count -eq 0) { continue }
            $carbsTotal = [math]::Round($carbsSum, 1)
            $foodsText = ($foodNames -join "、")
            $hunger = Get-Random -Minimum 1 -Maximum 4
            [void]$sql.AppendLine("INSERT INTO meal_record (user_id, record_date, node_code, foods_text, carbs_g, hunger_level, supplement_text, recorded_at) VALUES ($userId,'$date','$meal','$foodsText',$carbsTotal,$hunger,'【模拟数据】','$date $($mealTimes[$meal])');")
            [void]$sql.AppendLine("SET @mid := LAST_INSERT_ID();")
            [void]$sql.AppendLine("INSERT INTO meal_food_item (meal_id, food_id, food_name, weight_g, carbs_g, calories_kcal, sort_no) VALUES " + ($itemSql -join ", ") + ";")
        }
    }
}

$sqlPath = "$outDir\mock-data.sql"
[System.IO.File]::WriteAllText($sqlPath, $sql.ToString(), (New-Object System.Text.UTF8Encoding($false)))
"SQL 已生成: $sqlPath ($([math]::Round((Get-Item $sqlPath).Length/1KB))KB, $((Get-Content $sqlPath).Count) 行)"

$src = "source " + $sqlPath.Replace('\', '/')
& $mysql "--defaults-extra-file=$cnf" "--database=sugar_health" "--execute=$src" 2>&1
"执行退出码: $LASTEXITCODE"

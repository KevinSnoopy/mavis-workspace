import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/habit_provider.dart';
import '../models/habit.dart';
import '../theme/app_theme.dart';

class AnalyzerScreen extends StatefulWidget {
  const AnalyzerScreen({super.key});

  @override
  State<AnalyzerScreen> createState() => _AnalyzerScreenState();
}

class _AnalyzerScreenState extends State<AnalyzerScreen> {
  final TextEditingController _controller = TextEditingController();
  bool _isAnalyzing = false;
  AnalysisResult? _result;

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onTextChanged);
  }

  void _onTextChanged() => setState(() {});

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _analyze() async {
    if (_controller.text.trim().isEmpty) return;

    setState(() {
      _isAnalyzing = true;
      _result = null;
    });

    // 模拟分析延迟
    await Future.delayed(const Duration(milliseconds: 1500));

    final result = _analyzeText(_controller.text);
    setState(() {
      _isAnalyzing = false;
      _result = result;
    });
  }

  AnalysisResult _analyzeText(String text) {
    final keywords = <String, List<String>>{
      'career': ['工作', '职业', '转行', '上班', '辞职', '创业', '加薪', '晋升'],
      'study': ['考研', '考公', '学习', '考试', '读书', '考证', '学历'],
      'health': ['健康', '运动', '减肥', '睡眠', '饮食', '身体'],
      'finance': ['钱', '存款', '投资', '负债', '省钱', '赚钱', '收入'],
      'relationship': ['恋爱', '分手', '婚姻', '朋友', '同事', '领导', '家人'],
      'productivity': ['拖延', '效率', '专注', '时间', '计划'],
    };

    String matchedCategory = 'productivity';
    int maxMatch = 0;

    for (final entry in keywords.entries) {
      final matchCount = entry.value.where((p) => text.contains(p)).length;
      if (matchCount > maxMatch) {
        maxMatch = matchCount;
        matchedCategory = entry.key;
      }
    }

    final results = <String, AnalysisResult>{
      'career': AnalysisResult(
        mainConflict: '核心竞争力不足',
        secondaryConflict: '方向选择的焦虑',
        rootCause: '你担心的不是选择本身，而是选择后能否胜任。在能力不足时，任何方向都充满风险。',
        suggestion: '先花2-3个月专注培养一项可迁移的底层能力（写作、数据分析、沟通表达），技能到手后方向自然清晰。',
        suggestedHabits: [
          _SuggestedHabit('技能学习30分钟', '📚', '每天学习专业技能', AppColors.habitColors[0]),
          _SuggestedHabit('简历更新', '💼', '每周更新简历', AppColors.habitColors[4]),
          _SuggestedHabit('人脉拓展', '🤝', '每周认识一位同行', AppColors.habitColors[6]),
        ],
      ),
      'study': AnalysisResult(
        mainConflict: '目标与现状的差距',
        secondaryConflict: '失败恐惧',
        rootCause: '压力来自「万一失败」的假设，而非真实的困难。恐惧放大了障碍。',
        suggestion: '把大目标拆成每日可执行的小任务。今天背50个单词=考研成功，不要想"考研"两个字。',
        suggestedHabits: [
          _SuggestedHabit('晨间学习1小时', '🌅', '早起专注学习', AppColors.habitColors[2]),
          _SuggestedHabit('单词背诵30个', '📝', '每日词汇积累', AppColors.habitColors[8]),
          _SuggestedHabit('真题练习', '✍️', '每周完成一套真题', AppColors.habitColors[5]),
        ],
      ),
      'health': AnalysisResult(
        mainConflict: '理想与现实的落差',
        secondaryConflict: '意志力不足',
        rootCause: '你缺的不是意志力，而是「最小行动」。把"跑5公里"改成"穿上运动鞋出门"。',
        suggestion: '从微习惯开始：一个俯卧撑、提前一站下车、饭后站10分钟。习惯养成后再加量。',
        suggestedHabits: [
          _SuggestedHabit('每日运动30分钟', '🏃', '保持运动习惯', AppColors.habitColors[3]),
          _SuggestedHabit('23点前睡觉', '🌙', '保证充足睡眠', AppColors.habitColors[10]),
          _SuggestedHabit('健康饮食', '🥗', '每餐七分饱', AppColors.habitColors[7]),
        ],
      ),
      'finance': AnalysisResult(
        mainConflict: '收入与支出的矛盾',
        secondaryConflict: '缺乏财务规划',
        rootCause: '钱的问题本质是选择问题——我们把钱花在了"别人觉得重要"的事上。',
        suggestion: '记账一周看钱流向。决定：①砍一项非必要支出 ②每月储蓄10%收入。',
        suggestedHabits: [
          _SuggestedHabit('每日记账', '📊', '清楚每一笔支出', AppColors.habitColors[1]),
          _SuggestedHabit('强制储蓄', '💰', '每月储蓄20%', AppColors.habitColors[3]),
          _SuggestedHabit('理财学习', '📈', '每天学点理财', AppColors.habitColors[5]),
        ],
      ),
      'relationship': AnalysisResult(
        mainConflict: '期望与现实的落差',
        secondaryConflict: '沟通方式不当',
        rootCause: '多数人际问题是"我们没表达真实需求"，或"期待对方自动理解"。',
        suggestion: '沟通前先问自己："我最想让他理解的一件事是什么？"直接说出来。',
        suggestedHabits: [
          _SuggestedHabit('主动问候', '📱', '每天联系重要的人', AppColors.habitColors[9]),
          _SuggestedHabit('深度对话', '💬', '每周一次深度交流', AppColors.habitColors[6]),
          _SuggestedHabit('表达感谢', '🙏', '每天感谢一个人', AppColors.habitColors[2]),
        ],
      ),
      'productivity': AnalysisResult(
        mainConflict: '目标模糊导致行动瘫痪',
        secondaryConflict: '过度思考',
        rootCause: '「想太多」是追求完美方案。没有完美方案，只有"开始后才能看清"的路。',
        suggestion: '24小时规则：任何决定，24小时内必须有行动。不要等想清楚，在行动中想清楚。',
        suggestedHabits: [
          _SuggestedHabit('番茄工作法', '🍅', '每天4个番茄钟', AppColors.habitColors[1]),
          _SuggestedHabit('晨间规划', '📋', '早上列计划清单', AppColors.habitColors[4]),
          _SuggestedHabit('晚间复盘', '📓', '晚上总结反思', AppColors.habitColors[8]),
        ],
      ),
    };

    return results[matchedCategory]!;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text('⚖️ 矛盾分析器'),
      ),
      body: _result == null ? _buildInput() : _buildResult(),
    );
  }

  Widget _buildInput() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 步骤指示
          Row(
            children: [
              Container(
                width: 32,
                height: 32,
                decoration: const BoxDecoration(
                  color: AppTheme.primary,
                  shape: BoxShape.circle,
                ),
                child: const Center(
                  child: Text('1', style: TextStyle(fontWeight: FontWeight.bold)),
                ),
              ),
              const SizedBox(width: 8),
              Container(
                height: 2,
                width: 40,
                color: AppTheme.bgElevated,
              ),
              const SizedBox(width: 8),
              Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  color: AppTheme.bgElevated,
                  shape: BoxShape.circle,
                ),
                child: Center(
                  child: Text(
                    '2',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),

          // 输入框
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: AppTheme.bgCard,
              borderRadius: BorderRadius.circular(AppTheme.radiusLg),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '描述你现在的困惑',
                  style: TextStyle(
                    fontSize: 14,
                    color: AppTheme.textSecondary,
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _controller,
                  maxLines: null,
                  minLines: 4,
                  expands: false,
                  textInputAction: TextInputAction.done,
                  style: const TextStyle(fontSize: 16, height: 1.6),
                  onSubmitted: (_) => _analyze(),
                  decoration: InputDecoration(
                    hintText: '比如：想转行做程序员，但不知道学什么，怕学完还是找不到工作...',
                    hintStyle: TextStyle(color: AppTheme.textSecondary.withOpacity(0.5)),
                    filled: true,
                    fillColor: AppTheme.bgElevated,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(AppTheme.radiusMd),
                      borderSide: BorderSide.none,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: (_controller.text.trim().isEmpty || _isAnalyzing)
                        ? null
                        : _analyze,
                    child: _isAnalyzing
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Text('开始分析'),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 示例问题
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: AppTheme.bgCard,
              borderRadius: BorderRadius.circular(AppTheme.radiusLg),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '试试这些',
                  style: TextStyle(
                    fontSize: 12,
                    color: AppTheme.textSecondary,
                  ),
                ),
                const SizedBox(height: 12),
                ...[
                  '想减肥但总是坚持不下去',
                  '想转行但不知道该学什么',
                  '每天都很忙但没什么进步',
                ].map((q) => Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: GestureDetector(
                        onTap: () => _controller.text = q,
                        child: Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: AppTheme.bgElevated,
                            borderRadius: BorderRadius.circular(AppTheme.radiusSm),
                          ),
                          child: Text(q),
                        ),
                      ),
                    )),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildResult() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          // 主要矛盾
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: AppTheme.bgCard,
              borderRadius: BorderRadius.circular(AppTheme.radiusLg),
              border: Border(
                left: BorderSide(color: AppTheme.primary, width: 4),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Text('🔥', style: TextStyle(fontSize: 20)),
                    const SizedBox(width: 8),
                    Text(
                      '主要矛盾',
                      style: TextStyle(
                        fontSize: 14,
                        color: AppTheme.primary,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  _result!.mainConflict,
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  _result!.rootCause,
                  style: TextStyle(
                    fontSize: 14,
                    color: AppTheme.textSecondary,
                    height: 1.5,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 次要矛盾
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: AppTheme.bgCard,
              borderRadius: BorderRadius.circular(AppTheme.radiusLg),
              border: const Border(
                left: BorderSide(color: AppTheme.accent, width: 4),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Text('💡', style: TextStyle(fontSize: 20)),
                    const SizedBox(width: 8),
                    Text(
                      '次要矛盾',
                      style: TextStyle(
                        fontSize: 14,
                        color: AppTheme.accent,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  _result!.secondaryConflict,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '它会分散注意力，让你忽视真正重要的事',
                  style: TextStyle(
                    fontSize: 14,
                    color: AppTheme.textSecondary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 行动建议
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [
                  AppTheme.primary.withOpacity(0.1),
                  Colors.transparent,
                ],
              ),
              borderRadius: BorderRadius.circular(AppTheme.radiusLg),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Text('⚡', style: TextStyle(fontSize: 20)),
                    const SizedBox(width: 8),
                    const Text(
                      '行动建议',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  _result!.suggestion,
                  style: const TextStyle(
                    fontSize: 14,
                    height: 1.6,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 推荐的行动习惯
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: AppTheme.bgCard,
              borderRadius: BorderRadius.circular(AppTheme.radiusLg),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Row(
                      children: [
                        const Text('🎯', style: TextStyle(fontSize: 20)),
                        const SizedBox(width: 8),
                        const Text(
                          '转化为习惯',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                    Text(
                      '点击添加',
                      style: TextStyle(
                        fontSize: 12,
                        color: AppTheme.textSecondary,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                ..._result!.suggestedHabits.map((habit) {
                  return Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: _HabitCard(habit: habit),
                  );
                }),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 重新分析按钮
          TextButton(
            onPressed: () => setState(() => _result = null),
            child: const Text('← 重新分析'),
          ),

          const SizedBox(height: 32),
        ],
      ),
    );
  }
}

class _HabitCard extends StatefulWidget {
  final _SuggestedHabit habit;
  const _HabitCard({required this.habit});

  @override
  State<_HabitCard> createState() => _HabitCardState();
}

class _HabitCardState extends State<_HabitCard> {
  bool _added = false;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () async {
        if (_added) return;
        
        final provider = context.read<HabitProvider>();
        await provider.addHabit(Habit(
          id: '',
          name: widget.habit.name,
          description: widget.habit.description,
          icon: widget.habit.icon,
          colorValue: widget.habit.color,
          frequency: HabitFrequency.daily,
          createdAt: DateTime.now(),
        ));
        
        setState(() => _added = true);
        
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('已添加 ${widget.habit.name}'),
              backgroundColor: AppTheme.success,
            ),
          );
        }
      },
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: _added
              ? AppTheme.success.withOpacity(0.1)
              : AppTheme.bgElevated,
          borderRadius: BorderRadius.circular(AppTheme.radiusMd),
          border: Border.all(
            color: _added
                ? AppTheme.success.withOpacity(0.3)
                : Colors.transparent,
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: Color(widget.habit.color).withOpacity(0.2),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Center(
                child: Text(widget.habit.icon, style: const TextStyle(fontSize: 24)),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.habit.name,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    widget.habit.description,
                    style: TextStyle(
                      fontSize: 12,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            Text(
              _added ? '✓' : '+',
              style: TextStyle(
                color: _added ? AppTheme.success : AppTheme.primary,
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class AnalysisResult {
  final String mainConflict;
  final String secondaryConflict;
  final String rootCause;
  final String suggestion;
  final List<_SuggestedHabit> suggestedHabits;

  AnalysisResult({
    required this.mainConflict,
    required this.secondaryConflict,
    required this.rootCause,
    required this.suggestion,
    required this.suggestedHabits,
  });
}

class _SuggestedHabit {
  final String name;
  final String icon;
  final String description;
  final int color;

  _SuggestedHabit(this.name, this.icon, this.description, this.color);
}

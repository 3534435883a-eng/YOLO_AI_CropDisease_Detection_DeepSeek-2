"""Create the competition proposal from Codex-owned, real client responses.
Python >=3.10; reportlab and pypdf. No credentials or DuMate-generated files read.
"""
import argparse
import json
import os
from pathlib import Path
from xml.sax.saxutils import escape
from datetime import datetime
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether
from reportlab.graphics.shapes import Drawing, Rect, String, Line
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
GREEN = colors.HexColor('#235A46')
INK = colors.HexColor('#21352C')
MUTED = colors.HexColor('#64756A')
LIGHT = colors.HexColor('#EEF3EF')
PAPER = colors.HexColor('#FAFAF7')


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def make_pdf(output, evidence, font_dir):
    pdfmetrics.registerFont(TTFont('CN', str(font_dir / 'msyh.ttc'), subfontIndex=0))
    pdfmetrics.registerFont(TTFont('CNB', str(font_dir / 'msyhbd.ttc'), subfontIndex=0))
    a = load(evidence / 'analyze-response.json')['data']
    e = load(evidence / 'evaluate-response.json')['data']
    r = load(evidence / 'report-response.json')['data']
    task = load(evidence / 'task.json')
    goals = load(ROOT / 'docs/submission/goal-evidence/acceptance.json')
    outcomes = e['outcomes']
    order = list(outcomes)
    if len(order) != 7 or e['scenarioBinding'] != 'REFERENCE_ONLY_NOT_USER_CONDITIONED':
        raise ValueError('Unexpected reference scenario')
    W, H = A4
    usable = W - 88
    styles = {
        'body': ParagraphStyle('body', fontName='CN', fontSize=10, leading=17, textColor=INK, spaceAfter=9, wordWrap='CJK'),
        'small': ParagraphStyle('small', fontName='CN', fontSize=8, leading=12, textColor=MUTED, spaceAfter=6, wordWrap='CJK'),
        'h1': ParagraphStyle('h1', fontName='CNB', fontSize=23, leading=32, textColor=GREEN, spaceAfter=19, wordWrap='CJK'),
        'h2': ParagraphStyle('h2', fontName='CNB', fontSize=12, leading=19, textColor=GREEN, spaceBefore=10, spaceAfter=9, wordWrap='CJK'),
        'cover': ParagraphStyle('cover', fontName='CNB', fontSize=36, leading=48, textColor=GREEN, spaceAfter=20, wordWrap='CJK'),
        'mono': ParagraphStyle('mono', fontName='Courier', fontSize=7.5, leading=12, textColor=INK, spaceAfter=7),
        'cell': ParagraphStyle('cell', fontName='CN', fontSize=8, leading=12, textColor=INK, wordWrap='CJK'),
        'headcell': ParagraphStyle('headcell', fontName='CNB', fontSize=8, leading=12, textColor=GREEN, wordWrap='CJK'),
    }

    def p(text, kind='body'):
        return Paragraph(escape(str(text)).replace('\n', '<br/>'), styles[kind])

    def table(headers, rows, widths=None):
        data = [[p(v, 'headcell') for v in headers]] + [[p(v, 'cell') for v in row] for row in rows]
        t = Table(data, colWidths=widths or [usable / len(headers)] * len(headers), repeatRows=1, hAlign='LEFT')
        t.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), LIGHT), ('VALIGN', (0, 0), (-1, -1), 'TOP'),
            ('TOPPADDING', (0, 0), (-1, -1), 9), ('BOTTOMPADDING', (0, 0), (-1, -1), 9),
            ('LEFTPADDING', (0, 0), (-1, -1), 7), ('RIGHTPADDING', (0, 0), (-1, -1), 7),
            ('LINEBELOW', (0, 0), (-1, 0), .7, GREEN), ('LINEBELOW', (0, 1), (-1, -1), .35, colors.HexColor('#DCE4DD')),
        ]))
        return t

    def flow():
        d = Drawing(usable, 82)
        labels = [('用户需求', '自然语言'), ('DuMate', '规划与调用'), ('农业模型', '解析与仿真'), ('证据报告', '解释与复核')]
        bw = (usable - 36) / 4
        for i, (top, bottom) in enumerate(labels):
            x = i * (bw + 12)
            d.add(Rect(x, 8, bw, 62, rx=4, ry=4, fillColor=LIGHT, strokeColor=None))
            d.add(String(x + 12, 46, top, fontName='CNB', fontSize=11, fillColor=GREEN))
            d.add(String(x + 12, 24, bottom, fontName='CN', fontSize=8, fillColor=MUTED))
            if i < 3:
                d.add(Line(x + bw + 2, 38, x + bw + 10, 38, strokeColor=GREEN, strokeWidth=1))
        return d

    story = []
    def page(number, title):
        if story:
            story.append(PageBreak())
        story.extend([p(f'{number:02d} / HE XU AGRICULTURAL RESEARCH', 'small'), p(title, 'h1')])

    page(1, '应用方案')
    story += [Spacer(1, 20), p('禾序 Agent', 'cover'),
              p('基于 DuMate 的农业策略实验与智能决策辅助系统', 'h2'),
              p('2026 iCAN AI无代码智能体挑战赛\n参赛方向：DuMate智能体应用创新\n应用场景：教育科研 / 农业实验仿真'), Spacer(1, 18), flow(), Spacer(1, 14),
              p('用户提出研究目标，DuMate澄清基准与风险阈值，组织真实工具调用，筛选满足约束的研究候选，并交付可复算的实验报告；无合格方案时明确不推荐。'),
              table(['3项能力', '7组策略', '56天窗口'], [['农情分析 / 策略评测 / 报告', 'P0–P3业务组 + P4–P6消融组', '固定参考场景，seed=20250419']]), Spacer(1, 18),
              p('验证边界', 'h2'), p('本轮没有实棚验证。用户农情未注入模型初始条件；温室设备响应为仿真。M3历史观测回放与P0–P6合成天气评测分别说明。'),
              p('技术材料由Codex独立编写。技能包与软件调用链已验收；DuMate界面启用、分享链接和实际录屏以客户端实测为准。', 'small')]

    page(2, '目标用户与任务价值')
    story += [p('面向农业科研、教学和模型比较', 'h2'),
              p('研究者需要核对描述、运行对照、对齐指标、解释取舍并保存证据。手工整理容易把缺项当作已知值，把局部指标排名写成全面优势，或丢失批次与输入关联。'),
              table(['用户', '典型任务', '本作品交付'], [
                  ['科研与教学人员', '比较控制策略并复查实验假设', '七组矩阵、比较范围、参数与证据摘要'],
                  ['学生与学习者', '理解用水、能耗、病害风险的取舍', '数值解释、模型边界、人工复核'],
                  ['农业领域人员', '整理农情描述，识别需要确认的线索', '带原文草稿、缺项与冲突；非生产处方'],
              ], [110, 160, usable - 270]), p('智能体的作用', 'h2'),
              p('DuMate理解研究意图、展示任务计划、按依赖调用三个能力、阅读结果并交付解释。自包含Skill客户端承担稳定请求、证据保存和机械校验。农业后端的DeepSeek解析器负责字段抽取，已有确定性模型负责仿真。'),
              p('本次新增与原系统的分工', 'h2'),
              p('新增独立API适配层、受控客户端、可执行Skill、关联证据与报告。原YOLO、知识库、模型、Spring Boot业务及Vue界面保留；本作品没有将所有原接口开放给智能体。'),
              p('价值验证的范围', 'h2'),
              p('当前证明真实调用链和结果一致性，不给出未经实测的节省时间比例、真实增产比例或经营收益。对实际提效的量化，需要另做人工流程对照。')]

    page(3, '技术架构与访问边界')
    story += [flow(), p('独立适配层复用现有接口', 'h2'),
              table(['能力', '固定外部路径', '结果'], [
                  ['agri.analyze', '/v1/agri/analyze', 'analysisId、字段/原文、缺项、source'],
                  ['agri.evaluate', '/v1/agri/evaluate', 'evaluationId、batchId、P0–P6矩阵'],
                  ['agri.report', '/v1/agri/report', '关联证据、指标比较、复核与报告'],
              ], [92, 169, usable - 261]),
              p('上游仅调用农情parse、环境current、知识status、eval runs/matrix。报告读取适配层存档，不接收用户提供的矩阵。动态M3操作、用户管理、文件目录和设备管理均未对外开放。'),
              p('鉴权、预算与故障', 'h2'),
              table(['限制', '实现'], [
                  ['认证与地址', '独立Bearer；令牌留在.env/秘密变量。上游配置由管理员控制。'],
                  ['参数和负载', 'text最多6000字符；days 1–56；非负32位seed；32KiB请求；并发2。'],
                  ['预算和状态', '本层默认75秒、客户端90秒；判断HTTP及业务状态；错误映射不泄露原始错误页。'],
                  ['证据和重复调用', '稳定任务UUID与Idempotency-Key；原响应和摘要；24小时/200条本层证据。'],
              ], [105, usable - 105]),
              p('失败或中断不自动换键重发POST；客户端超时不等于同步上游已停止。SHA256用于变更核对，不是第三方认证或签名。', 'small')]

    page(4, '技能使用与用户流程')
    story += [p('自包含Skill，可直接调用', 'h2'),
              p('交付agri-research.zip，内含SKILL.md、Node客户端、报告生成器及执行说明，不含密钥。Node >=22即可运行，零npm依赖。安装后由DuMate理解目标、澄清指标与阈值，逐步执行并解释结果。目标模式增加本机decide，原三条HTTP接口不变。'),
              table(['步骤', '用户与智能体行为', '可见证据'], [
                  ['准备', '澄清目标、服务检查、保存原文及已确认目标', '基准/阈值/优先级、UUID与目标摘要'],
                  ['分析', '调用analyze，核对草稿和来源', '字段原文、缺项、冲突、风险线索'],
                  ['评测', '调用evaluate，保持seed/days及关联ID', '七组真实矩阵、profile与批次'],
                  ['筛选', '本机decide检查P0–P3，单列P4–P6', '各约束通过/淘汰原因、候选或无解'],
                  ['报告', '调用report并排版、解释权衡', '原始JSON、HTML/Markdown、人工复核'],
              ], [58, 238, usable - 296]),
              p('演示输入（用户陈述）', 'h2'),
              p(task['text']), p('安装与配置', 'h2'),
              p('原农业服务监听9999；start-adapter.ps1检查或启动本层9998并验证鉴权。技能自动读取已配置的本机.env，换机器由管理员设置DUMATE_ENV_FILE。不要将.env上传到作品或技能包。'),
              p('部署与分享状态', 'h2'),
              p('本轮独立验收为本机HTTP执行。云端节点不能访问本机127.0.0.1；远程运行需受控HTTPS地址和实际权限。文件包就位不等于DuMate界面已启用，也不等于作品已经公开发布。')]

    page(5, '真实资源与经济指标')
    story += [p(f'模型参数：seed={e["seed"]}；days={e["days"]}；场景={e["profile"]["profileId"]}。', 'small'),
              p('全部为当前模型假设下的模拟值。列示原值，不把负利润包装为盈利。P0–P3与P4–P6分组审阅。'),
              table(['策略', '可售产量\nkg', '用水\nm³', '能耗\nkWh', '成本\n元', '利润\n元'], [
                  [k.split('_')[0], outcomes[k]['marketableYieldKg'], outcomes[k]['waterUsedM3'], outcomes[k]['energyKWh'], outcomes[k]['costYuan'], outcomes[k]['profitYuan']] for k in order
              ], [48, 75, 75, 97, 100, usable - 395]),
              p('策略含义', 'h2'),
              table(['业务对照', '控制项消融'], [
                  ['P0：不做调控；P1：人工定时管理', 'P4：仅比例项P'],
                  ['P2：规则自动调控', 'P5：比例与积分P+I'],
                  ['P3：候选动作短程前瞻择优，不调用LLM', 'P6：完整P+I+D'],
              ]), p('当前输出意味着什么', 'h2'),
              p('本轮只有P3出现模拟可售产量；七组利润均为负值。P0用水与能耗为0，仍计入1456元模型成本。不能把零产量、零调控或负利润解释成真实农业经营的优势。'),
              p('模型输出不能替代实棚产量、收益或设备效果验证。56天窗口、多项假设参数和成本规则均限制外推。')]

    page(6, '风险指标与策略权衡')
    story += [table(['策略', '高温\n分钟', '高湿\n分钟', '病害压力\n模型积分', '最终模型\n严重度', '违规\n次'], [
                  [k.split('_')[0], outcomes[k]['highTemperatureMinutes'], outcomes[k]['highHumidityMinutes'], outcomes[k]['diseasePressureIntegral'], outcomes[k]['finalSeverityTotal'], outcomes[k]['constraintViolations']] for k in order
              ], [48, 80, 80, 123, 116, usable - 447]),
              p('相对P2，P3的实际取舍', 'h2'),
              p('用水少58.44m³，能耗多2412.4kWh，模拟利润低498.1463元。病害压力积分少134843.1，最终严重度高26.3992。病害积分与最终严重度衡量不同方面，不能相互替代。'),
              p('必须明确比较范围', 'h2'),
              p('P3病害压力积分在P0–P3业务组最低。比较全部七组时，病害积分最低为P6（2590174.2），总成本最高也为P6（5177.493元）。最终模型严重度最低为P4（45.7914）。服务的leaders只覆盖P0–P3。'),
              p('控制项消融的解释', 'h2'),
              p('P5/P6的高温暴露时长低于业务组，P5高湿暴露最少，但用水、能耗和成本需同时考虑。这是同一模型中的离线消融证据，不是可直接部署的农户处方。'),
              p('所有策略约束违规均为0，只表示本模型及本次规则下未记录违规，不证明真实设备或农业风险为0。', 'small')]

    page(7, '目标驱动筛选与无解说明')
    story += [p('AI明确研究目标，程序检验方案是否合格', 'h2'),
              p('DuMate将自然语言需求整理为基准、节水幅度、风险指标允许增幅、天数和优先级。模糊的“不能显著增加”先询问，不擅自设阈值。prepare冻结目标与摘要，evaluate仍复用既有参考模型。'),
              table(['步骤', '确定性判定'], [
                  ['硬约束', '逐项检查P0–P3的真实值与基准允许上限，并检查仿真约束违规为0。'],
                  ['排序', '仅排序合格方案；按用户指定优先级逐项比较，不使用猜测权重。'],
                  ['状态', '有改善则给研究候选；无合格方案或无基准改善则不推荐新方案。'],
                  ['证据', 'decide不发HTTP；报告校验摘要、关联编号并复算筛选结果。'],
              ], [80, usable - 80]),
              p('两组新任务的真实执行结果', 'h2'),
              table(['已确认演示目标', '实际结果'], [
                  ['56天，P2基准，至少节水10%，病害积分增幅0%', 'P3满足约束；它的最终严重度与能耗仍更高，必须披露。'],
                  ['保持上述条件，另要求最终严重度增幅0%', 'NO_FEASIBLE：四种管理策略无一同时合格，明确不推荐。'],
              ], [usable * .55, usable * .45]),
              p('本次仅枚举四种已有管理策略；P4–P6单列消融，不产生新控制动作或连续参数最优解。收紧条件应另建任务，不覆盖记录或静默放宽条件。'),
              p('两组评测批次（Codex客户端真实HTTP验收）', 'small'),
              *[p(item['mode'] + ': ' + item['evaluationId'] + ' / ' + item['status'], 'mono') for item in goals['records']],
              p('结果是固定未校准模型内的研究候选与无解说明；未注入用户农情，没有实棚验证。目标confirmed状态不是第三方授权或签名。', 'small')]

    page(8, '证据链与软件验收')
    story += [p('独立编写、独立真实验收', 'h2'),
              p('技术材料和本轮客户端由Codex独立制作，不读取或复用DuMate生成的交付部分。客户端实际调用现有服务，保存原始响应、调用键、时间、参数和结果摘要。'),
              table(['验证', '实际结果'], [
                  ['原后端', '437项测试通过，构建通过；本轮没有改动原源码、模型或配置。'],
                  ['适配层与客户端', '40项测试通过：权限/路由/参数、状态映射、锁与重复提交、摘要、比较范围。'],
                  ['真实调用', 'DeepSeek农情解析、P0–P6评测和报告均成功；真实JSON及客户端状态已保存。'],
                  ['重复调用', '相同任务成功步骤读取本机存档，不重新提交POST；原先独立seed/days重算指标逐值一致。'],
                  ['实际验证边界', '软件调用链和数据一致性；没有实棚或真实设备效果验证。'],
              ], [104, usable - 104]), p('本轮标识', 'h2'),
              p('任务UUID：' + task['taskId'], 'small'),
              p('analysisId: ' + a['analysisId'], 'mono'), p('evaluationId / batchId: ' + e['evaluationId'], 'mono'), p('reportId: ' + r['reportId'], 'mono'),
              p('农情结果SHA256', 'small'), p(a['resultSha256'], 'mono'),
              p('评测结果SHA256', 'small'), p(e['resultSha256'], 'mono'),
              p('这些摘要支持本机结果变更核对，不提供独立可信时间戳或第三方实棚认证。', 'small')]

    page(9, '人工复核与数据来源')
    for text in r['humanReview']:
        story.append(p('• ' + text))
    story += [p('三种来源分别展示', 'h2'),
              table(['来源', '含义与限制'], [
                  ['用户农情', '原文抽取草稿；症状与用户确诊声明不构成模型诊断。'],
                  ['环境状态', 'OBSERVED表示外部天气服务返回，非棚内传感器；SIMULATED_LOCATION表示演示模拟地。'],
                  ['模型参考场景', 'SIMULATED_UNCALIBRATED；合成天气、未校准初始物候及明确假设。'],
              ], [108, usable - 108]),
              p('M3与知识来源说明', 'h2'),
              p('原M3模块是公开历史观测顺序回放。本次eval没有注入M3回放时序。知识状态只说明已有库快照与可用性，不表示本报告执行了文献检索或取得诊断依据。'),
              p('参考profile中的数据标识（登记信息）', 'small'),
              p(e['profile']['sourceUrl'], 'small'), p(e['profile']['paperUrl'], 'small'),
              p('本轮没有重新核验论文全文或原始数据，也没有以这些链接证明设备响应、产量或利润已实测。', 'small')]

    page(10, '交付、展示与后续方向')
    story += [p('本轮可审核交付', 'h2'),
              table(['材料', '状态与用途'], [
                  ['独立Skill ZIP与源码', '客户端/脚本可执行验收；安装、启用与平台加载需实机确认。'],
                  ['正式方案、简介、讲稿', '提供明确角色分工、真实指标和边界，供参赛审核。'],
                  ['实验报告与原始证据', '本轮软件链路已执行，报告范围与原值可复查。'],
                  ['平台链接与录屏', '需真实DuMate客户端分享和录制，不由本机烟测冒充。'],
              ], [133, usable - 133]), p('公开提交步骤', 'h2'),
              p('在比赛账号安装启用Skill，提交演示需求，记录DuMate规划与三次调用，保存真实分享链接及全过程录屏。发布前由用户审核标题、简介和公开范围。方案PDF、简介与执行材料随平台链接提交。'),
              p('推广与后续验证', 'h2'),
              p('现阶段面向科研教学的模型比较与记录整理。可探索教学工具包和研究辅助服务；未验证付费、收入或生产提效。下一步应量化人工流程对照，补充参数/物候与观测支持，再考虑实棚校准和受约束生产验证。'),
              p('赛事与平台文档', 'h2'),
              p('用户提供的比赛介绍：https://www.saihuan.net/article/keji/299.html', 'small'),
              p('官方技能管理：https://cloud.baidu.com/doc/Dumate/s/5mmydgic3', 'small'),
              p('资料按2026-10-14核对。团队身份、平台实际发布和报名信息由用户确认，不编造作品地址。Skill为通知鼓励项，Workflow/MCP不是该介绍规定的必选方式。', 'small')]

    def frame(canvas, doc):
        canvas.saveState()
        canvas.setFillColor(PAPER)
        canvas.rect(0, 0, W, H, fill=1, stroke=0)
        canvas.setStrokeColor(colors.HexColor('#C8D7CE'))
        canvas.line(44, H - 36, W - 44, H - 36)
        canvas.setFont('CN', 7)
        canvas.setFillColor(MUTED)
        canvas.drawString(44, H - 25, '禾序 / 农业研究与虚拟策略对照')
        canvas.drawString(44, 25, '参考仿真 · 无实棚验证 · 2026-10-14')
        canvas.drawRightString(W - 44, 25, f'{doc.page:02d}')
        canvas.restoreState()

    output.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(str(output), pagesize=A4, leftMargin=44, rightMargin=44, topMargin=57, bottomMargin=48,
                            title='禾序 Agent - 农业策略实验与智能决策辅助系统', author='禾序农业研究项目', pageCompression=1)
    doc.build(story, onFirstPage=frame, onLaterPages=frame)
    reader = PdfReader(str(output))
    if len(reader.pages) != 10:
        raise ValueError(f'Expected ten deliberately composed pages, got {len(reader.pages)}')
    if output.stat().st_size > 50 * 1024 * 1024:
        raise ValueError('Proposal exceeds competition size limit')
    for index, page in enumerate(reader.pages):
        if not page.extract_text().strip():
            raise ValueError(f'Empty page {index + 1}')
    return {'pages': len(reader.pages), 'bytes': output.stat().st_size, 'file': str(output),
            'analysisId': a['analysisId'], 'evaluationId': e['evaluationId'], 'source': 'Codex independent client HTTP evidence', 'fieldValidation': 'NOT_PERFORMED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, default=ROOT / 'docs/submission/evidence')
    parser.add_argument('--font-dir', type=Path, default=Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Fonts')
    args = parser.parse_args()
    print(json.dumps(make_pdf(args.output, args.evidence, args.font_dir), ensure_ascii=False))

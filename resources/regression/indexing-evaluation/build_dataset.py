"""Freeze source-grounded questions before any variant is evaluated."""
from pathlib import Path
import json,re,hashlib,shutil
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parents[2]
manifest=json.loads((ROOT/'cookbook-manifest.json').read_text())
docs=manifest['documents'];cases=[]
# Each evidence selector must resolve against the untouched upstream Markdown.
qa=[
 [('腊八粥的豆类、花生和谷物分别至少浸泡多久？',['浸泡一夜','浸泡 3 小时'],['8|八','3|三']),('腊八粥先煮豆类多久，再加入谷物煮多久？',['小火煮 30 分钟','小火煮 60 分钟'],['30|三十','60|六十'])],
 [('蒲烧茄子先蒸多久，之后应该如何切开才能摊平？',['蒸 5','纵向切开'],['5|五','不要切断|不切断|别切断']),('蒲烧茄子的酱汁应浇到什么高度，收汁困难如何处理？',['没过 1/2','无法收汁'],['1/2|一半|半','淀粉'])],
 [('咖喱肥牛中土豆和胡萝卜要先翻炒多久，牛奶和肥牛加入后再煮多久？',['翻炒 2 分钟','加入肥牛卷以及牛奶'],['2|两|二','2.?3|两.?三']),('咖喱肥牛开始炒洋葱前，放多少油并等待多久？',['等待 10 秒'],['10','15','秒'])],
 [('奥利奥冰淇淋需要怎样处理饼干碎的粗细？',['一半奥奥变成粉状'],['一半|半数','0.5|半厘米']),('奥利奥冰淇淋应该放冷藏还是冷冻，温度和时长是多少？',['放置冰箱冷冻室'],['冷冻','18','4|四'])],
 [('小酥肉肉条的长度和厚度是多少？',['厚度 1.5 厘米'],['8','10','1.5']),('小酥肉腌制揉制到什么状态，接下来应怎样静置？',['直至肉吸收','冷藏室静置'],['粘手|黏手','冷藏','30|三十'])],
 [('河南蒸面条第一次蒸制时要淋多少油并蒸多久？',['淋 5ml'],['5','15']),('河南蒸面条五花肉需要切成多厚，炒制肉片多久？',['2mm 厚度','翻炒 1 分钟'],['2','1|一'])],
 [('日式肥牛丼饭肥牛先焯水多久，加水或出汁后大火等多久？',['焯水 1 分钟','等待 3 分钟'],['1|一','3|三']),('日式肥牛丼饭最后翻动食材多久，目的是什么？',['所有食材 **10 分钟'],['10|十','粘锅|黏锅'])],
 [('戚风蛋糕六寸和八寸的烘烤总时间分别是多少？',['烘烤总时间'],['30','35','50']),('戚风蛋糕应该选哪种模具，为什么不能随便用不粘模？',['铝合金阳极模具'],['铝合金|阳极','不沾|不粘'])],
 [('汤面里难熟食材在沸腾后煮多久，特别大块如何延长？',['沸腾后计时 10 分钟'],['10|十','5|五']),('汤面放入面条后煮多久，出现白色泡沫如何处理？',['液面易产生白色泡沫'],['5|五','锅盖'])],
 [('茄子肉煎饼加清水应调到什么状态才停止？',['停止加水'],['附着|挂住|包裹','不掉|不脱|不流']),('茄子肉煎饼两面金黄后还要煎多久，油变少怎么办？',['再煎**3-6分钟**'],['3','6','加.*油|补.*油'])],
 [('炒茄子倒酱油前翻炒多久，一人份酱油如何计量？',['翻炒约 40 秒'],['40','2.5']),('炒茄子为什么酱油宁少勿多？',['酱油可以少加'],['咸'])],
 [('咖喱炒蟹入锅时切口朝哪里，第一次与翻面各煎多久？',['螃蟹切口朝下'],['朝下','20','10']),('咖喱炒蟹为何要先煎切口，蟹盖又如何封住蟹黄？',['主要是封住蟹黄','热油泼到蟹盖'],['封','蟹黄','20'])],
 [('红烧鱼加冷水时水量和火力怎么控制，多久翻身？',['加入冷水'],['淹没|没过','中火','1|一']),('红烧鱼收汁到何时改小火，加入香菜葱花后多久关火？',['鱼的脊背线'],['小火','20'])],
 [('清蒸鳜鱼应该用多大火，蒸多久，关键窍门是什么？',['大火蒸制 8'],['大火','8','10']),('清蒸鳜鱼装盘为什么垫筷子，姜片放在哪里？',['下面垫一个筷子'],['均匀','姜片'])],
 [('尖叫牛蛙去腥时盐和啤酒至少各多少，手抓多久？',['10 克以上的盐巴'],['10','50','5|五']),('尖叫牛蛙过油多久应捞出，拖久会怎样？',['10 秒钟后捞出'],['10','老|柴'])],
 [('口水鸡煮鸡时大火烧开后如何调整火力，多久关火？',['中小火 20 分钟'],['中小火','20']),('口水鸡一份的鸡肉和辣椒粉分别是多少？',['鸡     半只','辣椒粉   20g'],['500|半只','20'])],
 [('枝竹羊腩煲香菇和腐竹分别提前泡多久？',['香菇提前浸泡'],['2','3','30']),('枝竹羊腩煲加盖后应以什么火力炖多久？',['小火炖 90 分钟'],['小火','90'])],
 [('香煎翘嘴鱼腌制和晾晒分别多久，应放在哪里腌制？',['冰箱保鲜层','晾晒至半干'],['1','2','冰箱|保鲜']),('香煎翘嘴鱼刚下锅为什么不急着移动，大约多久再晃锅？',['不要着急移动'],['破皮','30'])],
 [('芋泥雪媚娘蒸好的面团何时加黄油、加多少，之后冷藏多久？',['趁热加入黄油'],['趁热','30','一小时|1小时']),('芋泥雪媚娘一张皮和一份芋泥各多少克？',['分成 30g 一个'],['30'])],
 [('老式锅包肉的肉片要切多厚，哪些部位要去掉？',['厚度 8mm'],['8','筋膜']),('老式锅包肉加入小苏打后静置多久，再腌制多久？',['小苏打 5g','腌制 15 分钟'],['5','15'])]
]
for i,(doc,pairs) in enumerate(zip(docs,qa),1):
 text=(ROOT/doc['path']).read_text();lines=text.splitlines()
 for j,(question,selectors,checks) in enumerate(pairs,1):
  evidence=[]
  for selector in selectors:
   hits=[l.strip() for l in lines if selector in l]
   if not hits: raise ValueError((doc['key'],selector))
   evidence.append({'docKey':doc['key'],'quote':hits[0]})
  cases.append({'id':doc['key']+f'-{j}','sourceKind':'real-cookbook','split':doc['split'],'question':question,'answerable':True,'qrels':{doc['key']:3},'requiredEvidence':evidence,'answerChecks':checks,'referenceAnswer':'；'.join(e['quote'] for e in evidence)})
# Fictional software operating procedures, never presented as real user incidents.
names=['青岚库存同步','白鹭标签打印','松影批次导出','云舟离线采集','星桥账目对齐','竹海报表缓存','远帆图片归档','霁月目录同步','石径任务分发','晴川日志整理','栖霞文件投递','晨光清单合并','晚枫字段映射','秋池扫描校验','海棠消息归档','流萤库存快照','墨竹差异核验','银杏任务恢复','春溪权限同步','雪岭批量导入']
ops=['核对任务编号与当前日期是否相符','确认工作区内没有另一位操作者正在提交同一批次','检查输入文件编码并保留原始文件','记录开始时间和当前待处理数量','确认预览页没有出现重复主键提示','检查目标目录是否存在且名称与工单一致','从预览样本中选取首尾各一条记录对照源文件','将无关的历史记录从本次筛选条件中排除','核对显示总数与本次清单中的有效行数','确认字段映射与当前模板版本对应','记录异常行的行号和提示内容','检查过滤条件是否误排除了空值记录','确认系统时区与报表业务日期一致','保存本次操作的参数快照用于审计','核对批次标识在提交前后是否保持一致','检查目标端已经完成上一批次的归档','将预览结果与工单中的样例截图作比较','确认处理窗口内没有计划中的模板切换','检查任务历史里是否有同名但不同日期的条目','让复核人员确认本次操作的范围','核对文件摘要与上传完成后的摘要是否一致','确认所有告警已经有对应记录而非直接关闭提示','保留输入文件和预览结果的对应关系','完成操作后登记工单处理记录']
for i,name in enumerate(names,1):
 key=f'synth-{i:02}';wait=7+i;code=f'R{i:02}';threshold=100+i*7
 evidence1=f'当{name}出现{code}标记时，先暂停当前批次并保存校验报告。'
 evidence2=f'此时必须连续等待{wait}分钟，而且待处理数量低于{threshold}条后，才允许点击恢复。'
 evidence3=f'{name}恢复后的第一轮仅处理12条样本，样本全部校验通过后才能提交剩余记录。'
 paragraphs=[]
 count=24 if i%2==0 else 6
 for n,op in enumerate(ops[:count],1):
  paragraphs.append(f'检查项{n}：{op}。这一步的结论应写入{name}的处理记录；若发现差异，应先确认数据来源和本次范围，不要通过重复提交来掩盖提示。预览只用于核对，不会直接改变目标端内容。')
 # Realistic adjacent condition and pronoun; long variants also have detailed preceding procedure context.
 text=f'# {name}操作说明（合成测试语料）\n\n本文描述虚构系统，用于检索测试。\n\n## 批次检查与异常恢复\n\n'+ '\n\n'.join(paragraphs[:count//2])+ '\n\n'+evidence1+evidence2+evidence3+'\n\n'+'\n\n'.join(paragraphs[count//2:])+f'\n\n## 历史版本说明\n\n旧模板中曾出现等待3分钟的提示，该旧值不适用于本文的{code}标记。当前版本以异常恢复段落为准。\n'
 p=ROOT/'corpus'/'synthetic'/f'{key}.md';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 split='dev' if i<=5 else 'test';docs.append({'key':key,'path':str(p.relative_to(ROOT)),'source':'synthetic','sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'split':split})
 cases.append({'id':key+'-1','sourceKind':'synthetic','split':split,'question':f'{name}出现{code}标记后如何处理，满足哪些条件才可以恢复？','answerable':True,'qrels':{key:3},'requiredEvidence':[{'docKey':key,'quote':evidence1},{'docKey':key,'quote':evidence2}],'answerChecks':['暂停',str(wait),str(threshold)],'referenceAnswer':evidence1+evidence2})
 noanswer=i in [19,20]
 cases.append({'id':key+'-2','sourceKind':'synthetic','split':split,'question':f'{name}'+('的付费版本每月收费多少钱？' if noanswer else '恢复后能立即全量提交吗，应该如何验证？'),'answerable':not noanswer,'qrels':{} if noanswer else {key:3},'requiredEvidence':[] if noanswer else [{'docKey':key,'quote':evidence3}],'answerChecks':[] if noanswer else ['12','通过|成功'],'referenceAnswer':'文档未说明收费，不能推断。' if noanswer else evidence3})
old=json.loads((REPO/'resources/regression/retrieval-evaluation/datasets/bit-policy.json').read_text())
for key,source in old['sources'].items():
 p=ROOT/'corpus'/'regression'/(key+'.md');p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(REPO/source['path'],p)
 assert hashlib.sha256(p.read_bytes()).hexdigest()==source['sha256']
 docs.append({'key':key,'path':str(p.relative_to(ROOT)),'source':'existing-simulated-regression','sha256':source['sha256'],'split':'regression'})
for oldcase in old['cases']:
 evidence=[{'docKey':k,'quote':v} for k,v in oldcase.get('evidence',{}).items()]
 cases.append({**oldcase,'sourceKind':'existing-simulated-regression','split':'regression','requiredEvidence':evidence,'answerChecks':[],'referenceAnswer':'；'.join(e['quote'] for e in evidence) or '文档没有提供答案。'})
result={'version':1,'description':'真实文档构造题、合成工程测试题和现有模拟回归题；均非线上用户失败记录。先冻结题集，再从基线实际响应识别失败。','upstreamCommit':manifest['upstreamCommit'],'documents':docs,'cases':cases}
p=ROOT/'dataset.json';p.write_text(json.dumps(result,ensure_ascii=False,indent=2));print(len(docs),'documents',len(cases),'cases',hashlib.sha256(p.read_bytes()).hexdigest())

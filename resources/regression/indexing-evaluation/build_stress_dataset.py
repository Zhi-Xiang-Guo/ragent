from pathlib import Path
import json,hashlib
root=Path(__file__).resolve().parent
folder=root/'corpus/stress';folder.mkdir(exist_ok=True)
docs=[];cases=[]
products=['星泉归档','苍梧审计','霜桥备份','碧海推送','暮云对账','石溪路由','青竹快照','玉衡迁移']
terms=['松针回放','琥珀锁定','柳絮折返','银杏滞留','流萤错序','紫藤重影','砂砾漂移','秋水断续','冬青摇摆','鹭羽合流','芦苇漂白','榕荫延后','石斛空转','白桦回卷','青苔错位','月桂挂起']
def padded(prefix, text, length):
 value=prefix
 while len(value)<length:value+=text
 return value[:length-1]+'。'
for i,product in enumerate(products,1):
 key=f'stress-{i:02}'
 sections=[];evidence={}
 for j,term in enumerate(terms,1):
  lead=padded('值班人员应核对这次执行的环境信息，','记录任务时间、快照编号、审核批次、交接人员以及保留目录，',300)
  anchor=padded(f'检测到{term}异常时，必须进入当前条目的隔离流程，','该处置是本条目的专用操作，先保存现场再暂停执行，',150)
  middle=padded('该流程的现场核对需要覆盖工作区与控制台，','应逐项确认运行环境、只读副本、检查报表、日志保留位置与交接状态，',350)
  wait=23+i*3+j; limit=90+i*11+j*7
  constraint=padded(f'只有连续等待{wait}分钟，而且待处理数量严格低于{limit}条，才允许恢复当前流程，','此条件必须同时满足并由值班人员记录确认，',100)
  tail=padded('流程结束后的审计记录需要保存在本次任务目录，','核验结束状态、核验执行记录、检查复核结果、记录签字时间并整理交接清单，',650)
  sections.append(f'## 操作条目{j:02}\n\n'+ '\n\n'.join([lead,anchor,middle,constraint,tail]))
  evidence[j]=(anchor,constraint,wait,limit,term)
 text=f'# {product}操作手册（合成边界复现语料）\n\n本资料为合成测试文档，不对应真实企业制度。每个条目的恢复条件独立，不得借用其他条目的数字。\n\n'+'\n\n'.join(sections)+'\n'
 data=text.encode();path=folder/(key+'.md');path.write_bytes(data)
 docs.append({'key':key,'path':str(path.relative_to(root)),'source':'synthetic-boundary-reproducer','sha256':hashlib.sha256(data).hexdigest(),'split':'stress'})
 for j in [7,11]:
  anchor,constraint,wait,limit,term=evidence[j]
  cases.append({'id':f'{key}-{j}','sourceKind':'synthetic-stress-v2','split':'stress','question':f'{product}检测到{term}异常后，需要满足怎样的等待时间和待处理数量条件才能恢复？','answerable':True,'qrels':{key:3},'requiredEvidence':[{'docKey':key,'quote':anchor},{'docKey':key,'quote':constraint}],'answerChecks':[str(wait),str(limit)],'referenceAnswer':f'连续等待{wait}分钟，且待处理数量严格低于{limit}条，两个条件同时满足才允许恢复。'})
value={'version':2,'description':'观察到原冻结题集存在天花板后，独立构造的开发边界复现集；未修改原 dev/test/regression，不参与原测试集准确率计算。所有问题在任何策略运行此套题之前冻结。','documents':docs,'cases':cases}
p=root/'stress-dataset.json';p.write_text(json.dumps(value,ensure_ascii=False,indent=2));print(len(docs),len(cases),hashlib.sha256(p.read_bytes()).hexdigest())

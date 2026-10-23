"""Download a fixed, attributable sample of the upstream cookbook (no content rewriting)."""
import hashlib,json,random,urllib.request,urllib.parse
from pathlib import Path
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'corpus'/'cookbook'; OUT.mkdir(parents=True,exist_ok=True)
def get(url):
 return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'ragent-indexing-evaluation'}),timeout=60).read()
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--commit',default='cec956e80ae28d6c0ca84c9d39dddcf61ad633fd')
commit=parser.parse_args().commit
tree=json.loads(get(f'https://api.github.com/repos/datawhalechina/all-in-rag/git/trees/{commit}?recursive=1'))['tree']
items=[t for t in tree if t['path'].startswith('data/C8/cook/dishes/') and t['path'].endswith('.md') and 1500<=t.get('size',0)<=16000 and '/template/' not in t['path']]
random.Random(20261023).shuffle(items)
selected=items[:20]
manifest=[]
for i,item in enumerate(selected,1):
 data=get('https://raw.githubusercontent.com/datawhalechina/all-in-rag/'+commit+'/'+urllib.parse.quote(item['path']))
 name=f'cook-{i:02}.md';(OUT/name).write_bytes(data)
 manifest.append({'key':f'cook-{i:02}','path':'corpus/cookbook/'+name,'upstreamPath':item['path'],'source':'all-in-rag','sha256':hashlib.sha256(data).hexdigest(),'split':'dev' if i<=5 else 'test'})
(ROOT/'cookbook-manifest.json').write_text(json.dumps({'upstreamCommit':commit,'upstream':'https://github.com/datawhalechina/all-in-rag','documents':manifest},ensure_ascii=False,indent=2))
for t in tree:
 if t['path'].lower() in ['license','license.md','data/c8/cook/license','data/c8/cook/license.md']:
  (OUT/('UPSTREAM-'+t['path'].replace('/','-'))).write_bytes(get('https://raw.githubusercontent.com/datawhalechina/all-in-rag/'+commit+'/'+t['path']))
print('Downloaded',len(manifest),'documents at',commit)

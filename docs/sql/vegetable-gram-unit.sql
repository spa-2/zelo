-- 레시피에서 그램으로 재는 채소 11개의 기본 단위를 EA·BUNCH → G로 바꾼다
-- LLM이 그램으로 생각한 숫자를 개수 칸에 넣어(1인분에 대파 10단, 양파 30개) 견적에 그대로 담기던 문제
-- 시드는 데이터가 있으면 건너뛰므로, 이미 데이터가 있는 DB(로컬, AWS)는 이 파일을 한 번 돌린다
--
-- 상품 내용량(content_amount)은 지금 값(개·단 수) × 개당 무게로 바꾼다. 시드 CSV(products.csv)와 같은 기준이다
--   당근 200g, 브로콜리 300g, 파프리카 200g, 양배추 1통 2kg(1/2통 1kg), 양파 200g, 대파 1단 600g,
--   배추 1포기 2.5kg·알배추 1개 500g, 쪽파 1단 500g, 오이 200g, 애호박 300g, 단호박 1kg
-- 부하 테스트 상품(LT-)도 원본의 content_amount·unit_label을 그대로 복사했으므로 같은 식으로 바뀐다
-- unit_label("1단", "6개")은 그대로라 카드에 보이는 글자는 같다
--
-- 아직 G가 아닌 재료만 바꾸므로 두 번 돌려도 상품 양이 다시 곱해지지 않는다
-- 돌린 뒤 Redis 캐시를 지운다 (재료 단위 1시간 캐시, 상품 상세 캐시)
--   redis-cli --scan --pattern 'ingredientUnits::*' | xargs -r redis-cli del
--   redis-cli --scan --pattern 'productDetail::*' | xargs -r redis-cli del

begin;

update products p
set content_amount = p.content_amount * case
        when i.name = '양배추' and p.unit_label like '%1/2%' then 1000
        when i.name = '양배추' then 2000
        when i.name = '배추' and p.unit_label like '%포기%' then 2500
        when i.name = '배추' then 500
        when i.name = '당근' then 200
        when i.name = '브로콜리' then 300
        when i.name = '파프리카' then 200
        when i.name = '양파' then 200
        when i.name = '대파' then 600
        when i.name = '쪽파' then 500
        when i.name = '오이' then 200
        when i.name = '애호박' then 300
        when i.name = '단호박' then 1000
    end
from ingredients i
where p.ingredient_id = i.id
  and i.name in ('당근', '브로콜리', '파프리카', '양배추', '양파', '대파', '배추', '쪽파', '오이', '애호박', '단호박')
  and i.base_unit <> 'G';

update ingredients
set base_unit = 'G'
where name in ('당근', '브로콜리', '파프리카', '양배추', '양파', '대파', '배추', '쪽파', '오이', '애호박', '단호박')
  and base_unit <> 'G';

commit;

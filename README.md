# API LOAD TEST APPLICATION
Java 21 에 정식 도입된 가상 스레드(Virtual Thread)를 활용하여 대량의 HTTP 요청을 효율적으로 가능하게 처리하여 API 부하를 테스트 해볼수 있는 서비스

- 50,000개 동시 요청 처리 가능 (일반 스레드 대비 100배 이상)
- 메모리 사용량 90% 감소 (가상 스레드 vs 플랫폼 스레드)
- 실시간 모니터링 (WebSocket 기반 TPS, 성공률, 에러 추적)

<h2>💡 TPS (Transactions Per Second)란?  </h2>
<h3>TPS는 초당 처리 가능한 트랜잭션(요청) 수를 의미 </h3>
TPS = 총 요청 수 / 총 소요 시간(초)

예시:
- 10,000건 요청을 20초에 완료 → TPS = 500
- 의미: 이 서버는 초당 500건의 요청을 처리할 수 있음
  

## 1. API 부하 테스트 초기 화면
<img width="1297" height="608" alt="image" src="https://github.com/user-attachments/assets/bf3b8809-1690-4c6b-aee4-4d98697736a4" />

</br></br></br>

## 2. API 호출 시 화면
<img width="1297" height="555" alt="image" src="https://github.com/user-attachments/assets/18c39436-f3f5-4232-b453-6ba5ee4cd712" />

</br></br></br>

## 2-1. API 호출 시 화면 - 성공/실패 통계 차트
<img width="1107" height="825" alt="image" src="https://github.com/user-attachments/assets/bbc84320-d71c-4c06-909f-81d1266a968b" />


</br></br></br>

## 3. 대시보드 화면
<img width="1309" height="421" alt="image" src="https://github.com/user-attachments/assets/4df132a3-c6c0-4655-be54-3d8e5e34a23d" />

</br></br></br>

## 4. 테스트 이력 화면
<img width="1301" height="558" alt="image" src="https://github.com/user-attachments/assets/57da5442-7388-406a-9c50-b0edd6f0449e" />

</br></br></br>


------
java 21 / spring boot 3.5.7
gradle
jpa
DB : postgreSql
html/css/javascript



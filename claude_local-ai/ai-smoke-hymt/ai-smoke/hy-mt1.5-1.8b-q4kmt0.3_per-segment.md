# HY-MT 1.5 · 1.8B (Tencent) t0.3 per-segment

Nạp: 606 ms · arch=hunyuan-dense;desc=hunyuan-dense 1.8B Q4_K - Medium;ctx=4096;params=1791080448;vision=0;template={% if messages[0]['role'] == 'system' %}{% set l · vision=false

## Giao diện + phụ đề tiếng Trung — 4828 ms, 6 lượt gọi, prompt 33 tok (dùng lại 1) 333ms, sinh 5 tok 164ms (30.5 tok/s)

- `设置` → Thiết lập
- `账号与安全` → Tài khoản và An ninh
- `我们明天去北京看李老师吧。` → Ngày mai, chúng ta hãy đến Bắc Kinh để gặp giáo viên Lý đi.
- `他说你不用担心，一切都会好起来的。` → Anh ấy nói rằng bạn không cần phải lo lắng, mọi chuyện sẽ ổn thôi.
- `确定` → Chắc chắn rồi.
- `取消` → Hủy bỏ

## Tiếng Nga (có chữ OCR đọc nhầm La-tinh) — 2933 ms, 3 lượt gọi, prompt 64 tok (dùng lại 15) 548ms, sinh 11 tok 362ms (30.4 tok/s)

- `Пpивeт, кaк дeлa?` → Hôm nay, chúng ta sẽ học những từ mới.
- `Сегодня мы будем изучать новые слова` → Hôm nay, chúng ta sẽ học những từ mới.
- `Нажмите кнопку «Продолжить»` → Nhấn nút “Tiếp tục”.

## Tiếng Anh có lỗi OCR — 3161 ms, 4 lượt gọi, prompt 41 tok (dùng lại 1) 454ms, sinh 3 tok 97ms (30.9 tok/s)

- `Tap here to contiune` → Nhấn vào đây để tiếp tục.
- `Your account has been successfuly verifed` → Tài khoản của bạn đã được xác thực thành công.
- `Download the latest verslon of the app` → Tải về phiên bản mới nhất của ứng dụng này.
- `Settings` → Cài đặt

## Nhật + Hàn — 2447 ms, 3 lượt gọi, prompt 49 tok (dùng lại 11) 432ms, sinh 16 tok 557ms (28.7 tok/s)

- `今日は天気がいいですね。` → Hôm nay thời tiết thật tốt phải không?
- `ログイン` → Đăng nhập
- `안녕하세요, 만나서 반갑습니다.` → Xin chào bạn, thật vui được gặp bạn.

## Hội thoại nhiều câu (ngữ cảnh, xưng hô) — 3877 ms, 3 lượt gọi, prompt 45 tok (dùng lại 7) 430ms, sinh 22 tok 718ms (30.6 tok/s)

- `哥哥，你什么时候回来？` → Anh trai ơi, anh sẽ trở về vào tháng sau. Em phải chăm sóc mẹ thật tốt nhé.
- `我下个月就回家了，你要好好照顾妈妈。` → Tôi sẽ trở về nhà vào tháng tới. Bạn cần phải chăm sóc mẹ thật tốt.
- `知道了，你也要注意身体！` → Hiểu rồi, bạn cũng cần chú ý đến sức khỏe của mình nữa!


# HY-MT 1.5 · 1.8B (Tencent) Q8_0 greedy per-segment

Nạp: 225 ms · arch=hunyuan-dense;desc=hunyuan-dense 1.8B Q8_0;ctx=4096;params=1791080448;vision=0;template={% if messages[0]['role'] == 'system' %}{% set l · vision=false

## Giao diện + phụ đề tiếng Trung — 9419 ms, 6 lượt gọi, prompt 33 tok (dùng lại 1) 832ms, sinh 5 tok 270ms (18.5 tok/s)

- `设置` → Thiết lập
- `账号与安全` → Tài khoản và An ninh
- `我们明天去北京看李老师吧。` → Ngày mai chúng ta hãy đến Bắc Kinh để gặp cô Lý nhé.
- `他说你不用担心，一切都会好起来的。` → Anh ấy nói rằng bạn không cần phải lo lắng, mọi chuyện sẽ ổn thôi.
- `确定` → Chắc chắn rồi.
- `取消` → Hủy bỏ

## Tiếng Nga (có chữ OCR đọc nhầm La-tinh) — 6367 ms, 3 lượt gọi, prompt 64 tok (dùng lại 15) 1251ms, sinh 15 tok 838ms (17.9 tok/s)

- `Пpивeт, кaк дeлa?` → Xin chào, bạn làm việc như thế nào?
- `Сегодня мы будем изучать новые слова` → Hôm nay, chúng ta sẽ học những từ mới.
- `Нажмите кнопку «Продолжить»` → Hãy nhấn vào nút “Tiếp tục”.

## Tiếng Anh có lỗi OCR — 6441 ms, 4 lượt gọi, prompt 41 tok (dùng lại 1) 1013ms, sinh 3 tok 157ms (19.1 tok/s)

- `Tap here to contiune` → Nhấn vào đây để tiếp tục.
- `Your account has been successfuly verifed` → Tài khoản của bạn đã được xác thực thành công.
- `Download the latest verslon of the app` → Hãy tải về phiên bản mới nhất của ứng dụng này.
- `Settings` → Cài đặt

## Nhật + Hàn — 4841 ms, 3 lượt gọi, prompt 49 tok (dùng lại 11) 971ms, sinh 14 tok 743ms (18.8 tok/s)

- `今日は天気がいいですね。` → Hôm nay thời tiết thật dễ chịu phải không?
- `ログイン` → Đăng nhập
- `안녕하세요, 만나서 반갑습니다.` → Xin chào, rất vui được gặp bạn.

## Hội thoại nhiều câu (ngữ cảnh, xưng hô) — 6563 ms, 3 lượt gọi, prompt 45 tok (dùng lại 7) 972ms, sinh 22 tok 1193ms (18.4 tok/s)

- `哥哥，你什么时候回来？` → Anh trai ơi, anh sẽ trở về vào lúc nào?
- `我下个月就回家了，你要好好照顾妈妈。` → Tháng tới tôi sẽ trở về nhà. Bạn hãy chăm sóc mẹ mình thật tốt nhé.
- `知道了，你也要注意身体！` → Hiểu rồi, bạn cũng cần chú ý đến sức khỏe của mình nữa!


# HY-MT 1.5 · 1.8B (Tencent) Q8_0 greedy

Nạp: 209 ms · arch=hunyuan-dense;desc=hunyuan-dense 1.8B Q8_0;ctx=4096;params=1791080448;vision=0;template={% if messages[0]['role'] == 'system' %}{% set l · vision=false

## Giao diện + phụ đề tiếng Trung — 12064 ms, 3 lượt gọi, prompt 33 tok (dùng lại 1) 828ms, sinh 5 tok 278ms (18.0 tok/s)

- `设置` → Đặt cài đặt
- `账号与安全` → Tài khoản và bảo mật
- `我们明天去北京看李老师吧。` → Hôm mai chúng ta hãy đến Bắc Kinh để gặp giáo viên Lý đi.
- `他说你不用担心，一切都会好起来的。` → Giáo viên Lý nói rằng bạn không cần lo lắng, mọi chuyện sẽ ổn thôi.
- `确定` → Chắc chắn rồi.
- `取消` → Hủy bỏ

## Tiếng Nga (có chữ OCR đọc nhầm La-tinh) — 6855 ms, 1 lượt gọi, prompt 131 tok (dùng lại 1) 3303ms, sinh 62 tok 3550ms (17.5 tok/s)

- `Пpивeт, кaк дeлa?` → Xin chào, mọi người làm việc như thế nào rồi?
- `Сегодня мы будем изучать новые слова` → Hôm nay chúng ta sẽ học những từ mới.
- `Нажмите кнопку «Продолжить»` → Hãy nhấn nút “Tiếp tục”.

## Tiếng Anh có lỗi OCR — 5936 ms, 1 lượt gọi, prompt 120 tok (dùng lại 68) 1347ms, sinh 79 tok 4587ms (17.2 tok/s)

- `Tap here to contiune` → Xin vui lòng nhập thông tin ở đây để tiếp tục
- `Your account has been successfuly verifed` → Tài khoản của bạn đã được xác thực thành công
- `Download the latest verslon of the app` → Hãy tải về phiên bản mới nhất của ứng dụng
- `Settings` → Điều chỉnh

## Nhật + Hàn — 4246 ms, 1 lượt gọi, prompt 115 tok (dùng lại 68) 1239ms, sinh 53 tok 3006ms (17.6 tok/s)

- `今日は天気がいいですね。` → Hôm nay thời tiết thật tốt nhỉ.
- `ログイン` → Đăng nhập đi.
- `안녕하세요, 만나서 반갑습니다.` → Xin chào, rất vui được gặp bạn.

## Hội thoại nhiều câu (ngữ cảnh, xưng hô) — 5899 ms, 1 lượt gọi, prompt 112 tok (dùng lại 69) 1122ms, sinh 82 tok 4775ms (17.2 tok/s)

- `哥哥，你什么时候回来？` → Anh trai, anh sẽ về nhà lúc nào vậy?
- `我下个月就回家了，你要好好照顾妈妈。` → Tháng tới tôi sẽ về nhà, em phải chăm sóc mẹ thật tốt nhé.
- `知道了，你也要注意身体！` → Được rồi, anh cũng phải chú ý đến sức khỏe của mình nữa!


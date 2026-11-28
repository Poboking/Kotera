# NOTICE — 第三方归因与参考方式

## Emuera

Kotera 是一个 Kotlin/JVM 兼容实现，开发中同时使用了以下参考材料：

- **运行行为对拍**：用同一份脚本或输入分别运行 Emuera 与 Kotera，比较输出、变量、
  存档、错误与事件轨迹。
- **文档与源码考古**：查阅 Emuera 的说明、公开源码及反编译源码，用版本与
  `文件:行号` 解释边界行为、数据结构和控制流。
- **翻译与适配**：部分算法、数据结构、控制流和界面文本经过逐行或逐句翻译与适配。
  已知例子包括 PRINT 后缀解析、表达式运算符栈和 `HOTKEY.ERB` 解释器；具体出处保留在
  相应源码注释与 `specs/` 中。

因此，Kotera **不声称**自己只依据可观察行为编写，也不声称与 Emuera 源码无关。
源码行号是语义结论的溯源依据，不代表“没有参考源码”。参考对象包括 Emuera
1824+v24、EM+EE 谱系及 4.981 Skia fork，并非单一原版 1.824；每条语义结论以
`specs/` 中记录的版本和出处为准。

Kotera 仓库本身不分发 Emuera 二进制或完整源码树。上游材料仍受其各自许可约束。

## Emuera 原始许可（原文）

以下文字逐字抄录自 Emuera 1824+v24 随包 `Emuera_readme.txt` 的「ライセンス」一节；
4.981 Skia fork 随包 `Emuera.LICENSE.txt` 的正文与此一致。官方项目页：
<https://osdn.net/projects/emuera/>。

> [ライセンス]（zlib/libpngライセンス）
> Copyright (C) 2008- MinorShift, 妊）|дﾟ)の中の人
>
> 本ソフトウェアは「現状のまま」で、明示であるか暗黙であるかを問わず、何らの保証もなく提供されます。 本ソフトウェアの使用によって生じるいかなる損害についても、作者は一切の責任を負わないものとします。
>
> 以下の制限に従う限り、商用アプリケーションを含めて、本ソフトウェアを任意の目的に使用し、自由に改変して再頒布することをすべての人に許可します。
>
> 1.本ソフトウェアの出自について虚偽の表示をしてはなりません。あなたがオリジナルのソフトウェアを作成したと主張してはなりません。 あなたが本ソフトウェアを製品内で使用する場合、製品の文書に謝辞を入れていただければ幸いですが、必須ではありません。
> 2.ソースを変更した場合は、そのことを明示しなければなりません。オリジナルのソフトウェアであるという虚偽の表示をしてはなりません。
> 3.ソースの頒布物から、この表示を削除したり、表示の内容を変更したりしてはなりません。

中文说明（仅作导航，不替代上方原文）：该许可允许使用、修改和再分发，包括商业用途；
不得虚假表示出处或原创性，修改源码须明示，且不得从源码分发物中删除或改写上述声明。
产品文档中的谢辞是作者的希望，而非强制要求。

## 修改、再分发与 MIT 边界

- Kotera 中的翻译、适配和新增实现应注明其参考版本与改动性质，不得伪称为原版
  Emuera。对已判定为 bug 的行为，项目按 `specs/` 与在册问题处置，不以“兼容”为由
  隐瞒修改。
- 再分发 Kotera 修改版时，请保留本 NOTICE 与 Kotera 的 MIT 许可文本，并明示改动。
- 再分发源自 Emuera 或其 fork 的源码时，须保留对应上游许可声明、注明基础版本；
  Skia fork 的随包说明还要求派生作品附 Emuera 许可文件并写明 fork 基础版本。
- [LICENSE](LICENSE) 中的 MIT 许可覆盖 Kotera 原创且权利人有权授权的贡献；它不把
  Emuera 源码、译文、提取表或相关 fork 材料重新许可为 MIT，也不取代上游条件。

era 游戏运行时的原始概念源自 eramaker（by 佐藤敏 / サークル獏）。

## 参考树

| 参考对象 | 用途 | 公开入口 |
|---|---|---|
| Emuera 官方项目 | 原版说明与许可 | <https://osdn.net/projects/emuera/> |
| Emuera 1.824 源码镜像 | 早期语义考古；`specs/verify-notes.md` 记录 commit `301ce4a4a4d0b0b20a14428a4680cf3dbd3559d8` | <https://github.com/0x00000FF/Emuera> |
| EvilMask / Emuera.EM | EM+EE 谱系参考 | <https://gitlab.com/EvilMask/emuera.em> |
| minus010001 / Emuera Skia fork | 画蛇添足版 4.981 宿主行为参考 | <https://gitgud.io/minus010001/emuera_lazyloading_selfmodified_version> |

反编译树的本地路径用于开发溯源，不是公开读者可访问的许可或源码替代品。

## 内置字体（render/src/main/resources/fonts/）

| 字体 | 许可 | 声明文件 |
|---|---|---|
| Ark Pixel（方舟像素） | OFL 1.1 | `LICENSE-ArkPixel.txt` |
| Fusion Pixel（缝合像素） | OFL 1.1 | `LICENSE-FusionPixel.txt` |
| 美咲ゴシック（Misaki Gothic） | 其作者公布的自定义开放条款 | `LICENSE-Misaki.txt` |
| さいたまフォント（Saitamaar） | 公有领域 / mplus 开放条款（见文件） | `LICENSE-Saitamaar.txt` |
| GNU Unifont | SIL OFL 1.1 / GPL-2.0+ 双许可 | `LICENSE-Unifont.txt` |

(ns xhub-team.errors)

(def request-format-error
  {:error-data (list  {:error_code 1 :error_message "Не верный формат запроса"})})

(def email-validate-error
  {:error-data (list {:error_code 2 :error_message "Введите корректную почту"})})

(def password-validate-error
  {:error-data (list {:error_code 3 :error_message "Введите корректный пароль"})})

(def busy-email-error
  {:error-data (list {:error_code 6 :error_message "Пользователь с такой почтой уже зарегистрирован"})})

(defn error->aggregate [& errors]
  (reduce (fn [acc current-error]
            (merge-with (fn [old new] (concat old new))
                        acc
                        current-error))
          (if (sequential? errors) (first errors) errors)))

(def accept-code-error
  {:error-data (list {:error_code 4 :error_message "Код подтверждения не совпадает"})})

(def not_found_user_error
  {:error-data (list {:error_code 5 :error_message "Не верный логин или пароль"})})

(def user-not-auth
  {:error-data (list {:error_code 7 :error_message "Авторизируйтесь заново"})})

(def not_found_manga_by_id_error
  {:error-data (list {:error_code 8 :error_message "Манга не найдена"})})

(def is-not-uuid-error
  {:error-data (list {:error_code 9 :error_message "Не удалось преобразовать в UUID"})})

(def load-delete-permission-error
  {:error-data (list {:error_code 5 :error_message "Для загрузки или удаления изображений нужно быть автором или администратором"})})

